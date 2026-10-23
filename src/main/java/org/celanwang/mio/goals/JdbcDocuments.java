package org.celanwang.mio.goals;

import org.celanwang.mio.orchestration.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;

/** Local single-user workspace documents. Import/publish are explicit user actions, agent tools only read. */
public final class JdbcDocuments {
    public record Document(String id,String title,String content,long revision,long updatedAt,String sourceTask){}
    public record Save(String title,String content,long expectedRevision){}
    private final JdbcTemplate jdbc;private final CoordinationStore tasks;private final TransactionTemplate tx;
    private org.celanwang.mio.memory.EmbeddingClient embedding;private org.celanwang.mio.memory.jobs.MemoryCallBudget budget;
    public JdbcDocuments withKnowledge(org.celanwang.mio.memory.EmbeddingClient embedding,org.celanwang.mio.memory.jobs.MemoryCallBudget budget){this.embedding=embedding;this.budget=budget;return this;}
    public JdbcDocuments(JdbcTemplate jdbc,DataSource source,CoordinationStore tasks){this.jdbc=jdbc;this.tasks=tasks;tx=new TransactionTemplate(new DataSourceTransactionManager(source));}
    public void initialize(){jdbc.execute("CREATE TABLE IF NOT EXISTS workspace_documents(id TEXT PRIMARY KEY,title TEXT NOT NULL,content TEXT NOT NULL,revision BIGINT NOT NULL,updated_at BIGINT NOT NULL,source_task TEXT)");jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");jdbc.execute("CREATE TABLE IF NOT EXISTS document_chunks(document_id TEXT NOT NULL REFERENCES workspace_documents(id) ON DELETE CASCADE,revision BIGINT NOT NULL,start_offset INTEGER NOT NULL,end_offset INTEGER NOT NULL,content TEXT NOT NULL,namespace TEXT,embedding public.vector(1024),PRIMARY KEY(document_id,revision,start_offset))");}
    public List<Map<String,Object>> search(String query){
        if(query==null||query.length()>500)throw new IllegalArgumentException("文档查询过长");
        if(query.isBlank())return jdbc.queryForList("SELECT id,title,revision,updated_at,source_task,left(content,300) AS excerpt FROM workspace_documents ORDER BY updated_at DESC LIMIT 20");
        var lexical=jdbc.queryForList("SELECT d.id,d.title,d.revision,d.updated_at,d.source_task,c.start_offset,c.end_offset,left(c.content,500) AS excerpt,'KEYWORD' AS source FROM document_chunks c JOIN workspace_documents d ON d.id=c.document_id AND d.revision=c.revision WHERE position(lower(?) in lower(d.title||E'\\n'||c.content))>0 ORDER BY (position(lower(?) in lower(d.title))>0) DESC,d.updated_at DESC,d.id,c.start_offset LIMIT 60",query,query);
        var semantic=new ArrayList<Map<String,Object>>();
        if(embedding!=null&&embedding.available()&&!query.isBlank())try(var context=org.celanwang.mio.memory.jobs.MemoryWorkContext.open(budget,null)){
            var vectors=embedding.embed(List.of(query));if(vectors.isPresent()&&vectors.get().get(0).length==1024){String literal=literal(vectors.get().get(0));semantic.addAll(jdbc.queryForList("SELECT d.id,d.title,d.revision,d.updated_at,c.start_offset,c.end_offset,left(c.content,500) AS excerpt,'SEMANTIC' AS source,1-(c.embedding<=>?::public.vector) AS score FROM document_chunks c JOIN workspace_documents d ON d.id=c.document_id AND d.revision=c.revision WHERE c.namespace=? AND c.embedding IS NOT NULL AND 1-(c.embedding<=>?::public.vector)>=0.6 ORDER BY c.embedding<=>?::public.vector,d.id,c.start_offset LIMIT 40",literal,embedding.namespace(),literal,literal));}
        }catch(RuntimeException unavailable){/* Keyword references remain useful when semantic search is unavailable. */}
        return DocumentRanking.fuse(lexical,semantic);
    }
    public Document read(String id){return jdbc.query("SELECT * FROM workspace_documents WHERE id=?",(r,n)->new Document(r.getString("id"),r.getString("title"),r.getString("content"),r.getLong("revision"),r.getLong("updated_at"),r.getString("source_task")),id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("文档不存在"));}
    public Document save(String id,Save request,String sourceTask){
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,100}")||request==null||request.title()==null||request.title().isBlank()||request.title().length()>200||request.content()==null||request.content().isBlank()||request.content().length()>100000||request.expectedRevision()<0)throw new IllegalArgumentException("文档标题、正文或版本无效（正文最多10万字符）");
        return tx.execute(status->{
            long now=System.currentTimeMillis();int changed=request.expectedRevision()==0?jdbc.update("INSERT INTO workspace_documents VALUES (?,?,?,1,?,?) ON CONFLICT DO NOTHING",id,request.title(),request.content(),now,sourceTask):jdbc.update("UPDATE workspace_documents SET title=?,content=?,revision=revision+1,updated_at=?,source_task=? WHERE id=? AND revision=?",request.title(),request.content(),now,sourceTask,id,request.expectedRevision());
            if(changed!=1)throw new IllegalStateException("文档已修改，请刷新后重试");
            jdbc.update("DELETE FROM document_chunks WHERE document_id=?",id);for(int start=0;start<request.content().length();start+=1050){int end=Math.min(start+1200,request.content().length());jdbc.update("INSERT INTO document_chunks(document_id,revision,start_offset,end_offset,content) VALUES (?,?,?,?,?)",id,request.expectedRevision()+1,start,end,request.content().substring(start,end));if(end==request.content().length())break;}
            jdbc.update("INSERT INTO events(id,ts,session_id,node,type,title,detail) VALUES (?,?,NULL,'documents','document_saved','document_saved',?)","document:"+UUID.randomUUID(),now,id+":"+(request.expectedRevision()+1));return read(id);
        });
    }
    public Document publish(String session,String taskId,String title){var task=tasks.task(taskId).orElseThrow();if(!task.sessionId().equals(session)||task.status()!=TaskStatus.COMPLETED||task.invalidation()!=null)throw new IllegalArgumentException("只能保存本会话已完成且未失效的任务报告");return save(UUID.randomUUID().toString(),new Save(title,task.reply(),0),task.id());}
    public List<Map<String,Object>> chunks(String id){return jdbc.queryForList("SELECT c.revision,c.start_offset,c.end_offset,c.content,c.namespace FROM document_chunks c JOIN workspace_documents d ON d.id=c.document_id AND d.revision=c.revision WHERE d.id=? ORDER BY start_offset",id);}
    public int index(String id,long expected){
        return index(id,expected,()->{});
    }
    public int index(String id,long expected,Runnable publishGuard){
        if(embedding==null||!embedding.available())throw new IllegalStateException("向量服务未配置");var doc=read(id);if(doc.revision()!=expected)throw new IllegalStateException("文档版本已改变");
        var rows=jdbc.queryForList("SELECT start_offset,content FROM document_chunks WHERE document_id=? AND revision=? AND (namespace IS DISTINCT FROM ? OR embedding IS NULL) ORDER BY start_offset LIMIT 16",id,expected,embedding.namespace());if(rows.isEmpty())return 0;
        List<float[]> vectors;try(var context=org.celanwang.mio.memory.jobs.MemoryWorkContext.open(budget,null)){vectors=embedding.embed(rows.stream().map(r->(String)r.get("content")).toList()).orElseThrow(()->new IllegalStateException("向量服务未返回结果"));}
        return tx.execute(status->{publishGuard.run();jdbc.queryForObject("SELECT revision FROM workspace_documents WHERE id=? FOR UPDATE",Long.class,id);if(read(id).revision()!=expected)throw new IllegalStateException("文档版本已改变，丢弃旧索引结果");int changed=0;for(int i=0;i<rows.size();i++)changed+=jdbc.update("UPDATE document_chunks SET namespace=?,embedding=?::public.vector WHERE document_id=? AND revision=? AND start_offset=?",embedding.namespace(),literal(vectors.get(i)),id,expected,rows.get(i).get("start_offset"));return changed;});
    }
    public String namespace(){if(embedding==null||!embedding.available())throw new IllegalStateException("向量服务未配置");return embedding.namespace();}
    public Map<String,Object> citation(String id,long revision,int start,int end,String quote){
        var doc=read(id);if(doc.revision()!=revision)throw new IllegalStateException("引用版本已过期");
        if(start<0||end<=start||end>doc.content().length()||end-start>20000)throw new IllegalArgumentException("引用范围无效");
        String text=doc.content().substring(start,end);
        if(quote!=null&&!quote.equals(text))throw new IllegalArgumentException("引用文字与原文范围不一致");
        return Map.of("documentId",id,"title",doc.title(),"revision",revision,"startOffset",start,"endOffset",end,"content",text,"sourceHash",JsonFingerprint.hash(text),"verified",true);
    }
    public void delete(String id,long expected){tx.executeWithoutResult(status->{if(jdbc.update("DELETE FROM workspace_documents WHERE id=? AND revision=?",id,expected)!=1)throw new IllegalStateException("文档不存在或版本已改变");jdbc.update("INSERT INTO events(id,ts,node,type,title,detail) VALUES (?,?,'documents','document_deleted','document_deleted',?)","document:"+UUID.randomUUID(),System.currentTimeMillis(),id);});}
    private static String literal(float[] vector){if(vector==null||vector.length!=1024)throw new IllegalArgumentException("向量维度必须为1024");double norm=0;var s=new StringJoiner(",","[","]");for(float f:vector){if(!Float.isFinite(f))throw new IllegalArgumentException("向量无效");norm+=(double)f*f;s.add(Float.toString(f));}if(norm==0)throw new IllegalArgumentException("向量为零");return s.toString();}
}
