package org.celanwang.mio.goals;

import org.springframework.jdbc.core.JdbcTemplate;
import org.celanwang.mio.orchestration.*;
import java.util.*;

/** User-selected exact document spans. A matching source is not a verified assertion about its meaning. */
public final class GoalSourceReferences {
    public record Add(String documentId,long revision,int startOffset,int endOffset){}
    private final JdbcTemplate jdbc;private final JdbcDocuments documents;private final TaskRuntime runtime;
    public GoalSourceReferences(JdbcTemplate jdbc,JdbcDocuments documents,TaskRuntime runtime){this.jdbc=jdbc;this.documents=documents;this.runtime=runtime;}
    public void add(String session,Add request){
        if((long)request.endOffset()-request.startOffset()>2000)throw new IllegalArgumentException("每条论据最多2000字符");
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        tx.executeWithoutResult(status->{
            var goal=runtime.goal(session);jdbc.queryForObject("SELECT id FROM goals WHERE id=? FOR UPDATE",String.class,goal.id());runtime.goal(session).requireActive();
            jdbc.queryForObject("SELECT revision FROM workspace_documents WHERE id=? FOR SHARE",Long.class,request.documentId());var citation=documents.citation(request.documentId(),request.revision(),request.startOffset(),request.endOffset(),null);
            if(jdbc.queryForObject("SELECT count(*) FROM goal_source_references WHERE goal_id=?",Long.class,goal.id())>=40)throw new IllegalStateException("每个目标至多40条显式文档论据");
            jdbc.update("INSERT INTO goal_source_references VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(goal_id,document_id,document_revision,start_offset,end_offset) DO NOTHING",UUID.randomUUID().toString(),goal.id(),request.documentId(),request.revision(),request.startOffset(),request.endOffset(),citation.get("content"),System.currentTimeMillis());
        });
    }
    public List<Map<String,Object>> list(String session){
        var rows=jdbc.queryForList("SELECT r.*,d.title,d.revision AS current_revision FROM goal_source_references r JOIN goals g ON g.id=r.goal_id JOIN workspace_documents d ON d.id=r.document_id WHERE g.session_id=? ORDER BY r.created_at DESC,r.id DESC LIMIT 40",session);
        for(var row:rows){row.put("type","DOCUMENT");row.put("referenceId","d_"+row.get("id"));row.put("state",Objects.equals(row.get("document_revision"),row.get("current_revision"))?"CURRENT":"STALE");row.put("scope","原文片段，不是执行指令；引用一致不证明观点正确");}return rows;
    }
    public void remove(String session,String id){jdbc.update("DELETE FROM goal_source_references r USING goals g WHERE r.goal_id=g.id AND g.session_id=? AND r.id=?",session,id);}
}
