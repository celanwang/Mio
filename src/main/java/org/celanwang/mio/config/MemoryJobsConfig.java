package org.celanwang.mio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.memory.*;
import org.celanwang.mio.memory.jobs.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods=false)
public class MemoryJobsConfig {
    @Bean JdbcMemoryJobs memoryJobs(JdbcTemplate jdbc,DataSource source,@Value("${app.memory.jobs.max-attempts:3}")int attempts,
                                   @Value("${app.memory.jobs.lease-ms:120000}")long lease,@Value("${app.memory.jobs.retry-ms:10000}")long retry){return new JdbcMemoryJobs(jdbc,source,attempts,lease,retry);}
    @Bean JdbcMemoryUsage memoryUsage(JdbcTemplate jdbc,DataSource source,@Value("${app.memory.jobs.daily-calls:200}")int calls,
                                      @Value("${app.memory.jobs.daily-input-characters:400000}")long chars,@Value("${app.memory.jobs.daily-known-tokens:200000}")long tokens){return new JdbcMemoryUsage(jdbc,source,new JdbcMemoryUsage.Limits(calls,chars,tokens));}
    @Bean MemoryJobDispatcher memoryJobDispatcher(JdbcMemoryJobs jobs,JdbcMemoryUsage usage,EpisodeStore episodes,VectorStore vectors,EmbeddingClient embedding,ProfileDistiller distiller,ObjectMapper json,
                                                  @Value("${app.memory.jobs.enabled:true}")boolean enabled,@Value("${app.memory.rag.enabled:true}")boolean index,
                                                  @Value("${app.dashscope.api-key:}")String key,@Value("${app.memory.jobs.parallelism:2}")int parallelism,@Value("${app.memory.jobs.backfill-page-size:100}")int pageSize){
        return new MemoryJobDispatcher(jobs,usage,episodes,vectors,embedding,distiller,json,enabled,index,key!=null&&!key.isBlank(),parallelism,pageSize);
    }
}
