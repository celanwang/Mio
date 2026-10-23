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
    public JdbcDocuments(JdbcTemplate jdbc,DataSource source,CoordinationStore tasks){this.jdbc=jdbc;this.tasks=tasks;tx=new TransactionTemplate(new DataSourceTransactionManager(source));}
    public void initialize(){jdbc.execute("CREATE TABLE IF NOT EXISTS workspace_documents(id TEXT PRIMARY KEY,title TEXT NOT NULL,content TEXT NOT NULL,revision BIGINT NOT NULL,updated_at BIGINT NOT NULL,source_task TEXT)");}
    public List<Map<String,Object>> search(String query){
        if(query==null||query.length()>500)throw new IllegalArgumentException("文档查询过长");
        return jdbc.queryForList("SELECT id,title,revision,updated_at,source_task,left(content,300) AS excerpt FROM workspace_documents WHERE position(lower(?) in lower(title||E'\\n'||content))>0 ORDER BY updated_at DESC LIMIT 20",query);
    }
    public Document read(String id){return jdbc.query("SELECT * FROM workspace_documents WHERE id=?",(r,n)->new Document(r.getString("id"),r.getString("title"),r.getString("content"),r.getLong("revision"),r.getLong("updated_at"),r.getString("source_task")),id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("文档不存在"));}
    public Document save(String id,Save request,String sourceTask){
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,100}")||request==null||request.title()==null||request.title().isBlank()||request.title().length()>200||request.content()==null||request.content().isBlank()||request.content().length()>100000||request.expectedRevision()<0)throw new IllegalArgumentException("文档标题、正文或版本无效（正文最多10万字符）");
        return tx.execute(status->{
            long now=System.currentTimeMillis();int changed=request.expectedRevision()==0?jdbc.update("INSERT INTO workspace_documents VALUES (?,?,?,1,?,?) ON CONFLICT DO NOTHING",id,request.title(),request.content(),now,sourceTask):jdbc.update("UPDATE workspace_documents SET title=?,content=?,revision=revision+1,updated_at=?,source_task=? WHERE id=? AND revision=?",request.title(),request.content(),now,sourceTask,id,request.expectedRevision());
            if(changed!=1)throw new IllegalStateException("文档已修改，请刷新后重试");
            jdbc.update("INSERT INTO events(id,ts,session_id,node,type,title,detail) VALUES (?,?,NULL,'documents','document_saved','document_saved',?)","document:"+UUID.randomUUID(),now,id+":"+(request.expectedRevision()+1));return read(id);
        });
    }
    public Document publish(String session,String taskId,String title){var task=tasks.task(taskId).orElseThrow();if(!task.sessionId().equals(session)||task.status()!=TaskStatus.COMPLETED||task.invalidation()!=null)throw new IllegalArgumentException("只能保存本会话已完成且未失效的任务报告");return save(UUID.randomUUID().toString(),new Save(title,task.reply(),0),task.id());}
}
