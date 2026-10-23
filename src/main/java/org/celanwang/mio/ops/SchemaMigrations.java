package org.celanwang.mio.ops;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.security.MessageDigest;
public final class SchemaMigrations {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public SchemaMigrations(JdbcTemplate jdbc,DataSource source){this.jdbc=jdbc;tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(120);}
    public void migrate(){tx.executeWithoutResult(status->{
        jdbc.execute("SELECT set_config('search_path',quote_ident(current_schema())||', public',true)");
        jdbc.execute("SELECT pg_advisory_xact_lock(hashtext(current_database()),hashtext(current_schema()))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY,name TEXT NOT NULL,checksum TEXT NOT NULL,applied_at BIGINT NOT NULL)");
        int version=0;for(String name:List.of("V001__unified_schema.sql","V002__knowledge_backfill.sql","V003__evidence_decisions.sql","V004__goal_decisions.sql","V005__goal_syntheses.sql","V006__personal_outcomes.sql")){version++;var resource=new ClassPathResource("db/migrations/"+name);String hash;try(var input=resource.getInputStream()){hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));}catch(Exception e){throw new IllegalStateException("迁移资源不可读",e);}
            var old=jdbc.queryForList("SELECT checksum FROM schema_migrations WHERE version=?",String.class,version);if(!old.isEmpty()){if(!old.get(0).equals(hash))throw new IllegalStateException("已应用迁移被修改，请新增迁移版本");continue;}
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection->{ScriptUtils.executeSqlScript(connection,new EncodedResource(resource,java.nio.charset.StandardCharsets.UTF_8));return null;});
            jdbc.update("INSERT INTO schema_migrations VALUES (?,?,?,floor(extract(epoch FROM clock_timestamp())*1000))",version,name,hash);
        }
    });}
}
