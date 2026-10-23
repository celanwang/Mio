package org.celanwang.mio.goals;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;

/** Explicit opt-in indexing, one bounded batch per claim; no automatic import spending. */
public final class DocumentIndexJobs {
    public record Claim(String id,String documentId,long revision,String namespace,String token) {}
    private final JdbcTemplate jdbc;
    private final JdbcDocuments documents;
    private final TransactionTemplate tx;

    public DocumentIndexJobs(JdbcTemplate jdbc,DataSource source,JdbcDocuments documents) {
        this.jdbc=jdbc;this.documents=documents;
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    public Map<String,Object> start(String id,long revision) {
        if(documents.read(id).revision()!=revision)throw new IllegalStateException("文档版本已改变");
        String namespace=documents.namespace();
        return tx.execute(status->{
            jdbc.update("INSERT INTO document_index_jobs(id,document_id,revision,namespace,state,updated_at) VALUES (?,?,?,?,'QUEUED',?) ON CONFLICT(document_id,revision,namespace) DO UPDATE SET state=CASE WHEN document_index_jobs.state='RUNNING' THEN 'RUNNING' ELSE 'QUEUED' END,attempts=0,next_at=0,last_error='',updated_at=EXCLUDED.updated_at",UUID.randomUUID().toString(),id,revision,namespace,System.currentTimeMillis());
            return status(id).stream().filter(r->((Number)r.get("revision")).longValue()==revision&&r.get("namespace").equals(namespace)).findFirst().orElseThrow();
        });
    }

    public List<Map<String,Object>> status(String id) {
        return jdbc.queryForList("SELECT j.id,j.revision,j.namespace,j.state,j.attempts,j.last_error,j.updated_at,(SELECT count(*) FROM document_chunks c WHERE c.document_id=j.document_id AND c.revision=j.revision) AS total,(SELECT count(*) FROM document_chunks c WHERE c.document_id=j.document_id AND c.revision=j.revision AND c.namespace=j.namespace AND c.embedding IS NOT NULL) AS indexed FROM document_index_jobs j WHERE document_id=? ORDER BY updated_at DESC LIMIT 20",id);
    }

    public void pause(String document,String job) {
        if(jdbc.update("UPDATE document_index_jobs SET state='PAUSED',lease_token=NULL,lease_until=0,updated_at=? WHERE document_id=? AND id=? AND state IN ('QUEUED','RUNNING','FAILED')",System.currentTimeMillis(),document,job)!=1)
            throw new IllegalStateException("索引任务不可暂停，请刷新状态");
    }

    public Optional<Claim> claim() {
        return tx.execute(status->{
            long now=System.currentTimeMillis();
            var rows=jdbc.queryForList("SELECT * FROM document_index_jobs WHERE (state='QUEUED' AND next_at<=?) OR (state='RUNNING' AND lease_until<=?) ORDER BY updated_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",now,now);
            if(rows.isEmpty())return Optional.empty();
            var row=rows.get(0);String token=UUID.randomUUID().toString(),id=(String)row.get("id");
            jdbc.update("UPDATE document_index_jobs SET state='RUNNING',lease_token=?,lease_until=?,updated_at=? WHERE id=?",token,now+120000,now,id);
            return Optional.of(new Claim(id,(String)row.get("document_id"),((Number)row.get("revision")).longValue(),(String)row.get("namespace"),token));
        });
    }

    public void run(Claim claim) {
        try {
            if(!documents.namespace().equals(claim.namespace())||documents.read(claim.documentId()).revision()!=claim.revision()) {
                finish(claim,"CANCELLED","");return;
            }
            documents.index(claim.documentId(),claim.revision(),()->fence(claim));
            tx.executeWithoutResult(status->{
                fence(claim);
                long remaining=jdbc.queryForObject("SELECT count(*) FROM document_chunks WHERE document_id=? AND revision=? AND (namespace IS DISTINCT FROM ? OR embedding IS NULL)",Long.class,claim.documentId(),claim.revision(),claim.namespace());
                jdbc.update("UPDATE document_index_jobs SET state=?,attempts=0,lease_token=NULL,lease_until=0,next_at=?,updated_at=?,last_error='' WHERE id=?",remaining==0?"COMPLETED":"QUEUED",System.currentTimeMillis()+1000,System.currentTimeMillis(),claim.id());
            });
        } catch(Exception failure) {
            // Do not persist provider response bodies or credentials in job errors.
            jdbc.update("UPDATE document_index_jobs SET state=CASE WHEN attempts>=2 THEN 'FAILED' ELSE 'QUEUED' END,attempts=attempts+1,lease_token=NULL,lease_until=0,next_at=?,last_error='INDEX_UNAVAILABLE',updated_at=? WHERE id=? AND lease_token=? AND state='RUNNING'",System.currentTimeMillis()+30000,System.currentTimeMillis(),claim.id(),claim.token());
        }
    }

    private void fence(Claim claim) {
        var rows=jdbc.queryForList("SELECT id FROM document_index_jobs WHERE id=? AND lease_token=? AND state='RUNNING' AND lease_until>? FOR UPDATE",claim.id(),claim.token(),System.currentTimeMillis());
        if(rows.isEmpty())throw new IllegalStateException("索引租约已失效或已暂停");
    }
    private void finish(Claim claim,String state,String error) {
        jdbc.update("UPDATE document_index_jobs SET state=?,last_error=?,lease_token=NULL,lease_until=0,updated_at=? WHERE id=? AND lease_token=?",state,error,System.currentTimeMillis(),claim.id(),claim.token());
    }
}
