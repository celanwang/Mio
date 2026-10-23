package org.celanwang.mio.memory;

import com.zaxxer.hikari.*;
import org.celanwang.mio.memory.jobs.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;

/** Disposable schema inside the explicitly supplied evaluation database; never reads application credentials. */
final class MemoryJobsDatabase implements AutoCloseable {
    final JdbcTemplate admin,jdbc;final HikariDataSource source;final String schema;
    final JdbcMemoryJobs jobs;final JdbcMemoryUsage usage;final PgEpisodeStore episodes;final ProfileStore profiles;final PgEntityStore entities;final PgVectorStore vectors;
    MemoryJobsDatabase(){
        String url=System.getenv("MIO_TEST_PG_URL"),user=System.getenv().getOrDefault("MIO_TEST_PG_USERNAME","postgres"),password=System.getenv().getOrDefault("MIO_TEST_PG_PASSWORD","");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));admin.execute("CREATE EXTENSION IF NOT EXISTS vector");
        schema="mio_jobs_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername(user);config.setPassword(password);config.setMaximumPoolSize(6);config.setConnectionTimeout(5000);config.setConnectionInitSql("SET search_path TO "+schema+",public");
        source=new HikariDataSource(config);jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE messages(id TEXT PRIMARY KEY,session_id TEXT,ts BIGINT NOT NULL,role TEXT NOT NULL,text TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE embeddings(id TEXT PRIMARY KEY,embedding vector(1024) NOT NULL)");
        jdbc.execute("CREATE TABLE entities(id BIGSERIAL PRIMARY KEY,subject TEXT NOT NULL,predicate TEXT NOT NULL,object TEXT NOT NULL,valid_from BIGINT NOT NULL,valid_to BIGINT,source_ts BIGINT NOT NULL)");
        ProfileStore.initializeSchema(jdbc);PgEntityStore.initializeDerivations(jdbc);
        jobs=new JdbcMemoryJobs(jdbc,source,3,120000,1);jobs.initialize();jobs.initialize();
        usage=new JdbcMemoryUsage(jdbc,source,new JdbcMemoryUsage.Limits(10000,10000000,10000000));usage.initialize();
        episodes=new PgEpisodeStore(jdbc,jobs,true,true);profiles=new ProfileStore(jdbc);entities=new PgEntityStore(jdbc);vectors=new PgVectorStore(jdbc);
    }
    EpisodeStore.Episode append(String id,boolean index,boolean distill){var episode=new EpisodeStore.Episode(id,"synthetic-session",System.currentTimeMillis(),"user","偏好 "+id);jobs.append(episode,index,distill);return episode;}
    void due(String id){jdbc.update("UPDATE memory_jobs SET next_attempt_at=0 WHERE id=?",id);}
    void expire(String id){jdbc.update("UPDATE memory_jobs SET lease_until=0 WHERE id=?",id);}
    @Override public void close(){source.close();admin.execute("DROP SCHEMA "+schema+" CASCADE");}
}
