package org.celanwang.mio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.memory.*;
import org.celanwang.mio.memory.jobs.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryJobsConfigTest {
    @Test void wiringIsLazyAndDisabledConsumersNeverAccessDatabaseOrProviders(){
        new ApplicationContextRunner().withUserConfiguration(Dependencies.class,MemoryJobsConfig.class).withPropertyValues("app.memory.jobs.enabled=false").run(context->{
            assertThat(context).hasSingleBean(JdbcMemoryJobs.class).hasSingleBean(MemoryCallBudget.class).hasSingleBean(MemoryJobDispatcher.class);
            assertThat(context.getBean(MemoryJobDispatcher.class).runOne()).isFalse();
            verify(context.getBean(JdbcTemplate.class)).afterPropertiesSet();verifyNoMoreInteractions(context.getBean(JdbcTemplate.class));
            verifyNoInteractions(context.getBean(DataSource.class),context.getBean(EmbeddingClient.class),context.getBean(ProfileDistiller.class));
        });
    }
    @Configuration(proxyBeanMethods=false) static class Dependencies {
        @Bean DataSource source(){return mock(DataSource.class);}@Bean JdbcTemplate jdbc(){return mock(JdbcTemplate.class);}
        @Bean ObjectMapper json(){return new ObjectMapper();}@Bean EpisodeStore episodes(){return mock(EpisodeStore.class);}
        @Bean VectorStore vectors(){return mock(VectorStore.class);}@Bean EmbeddingClient embedding(){return mock(EmbeddingClient.class);}
        @Bean ProfileDistiller distiller(){return mock(ProfileDistiller.class);}
    }
}
