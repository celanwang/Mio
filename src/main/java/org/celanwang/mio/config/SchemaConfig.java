package org.celanwang.mio.config;
import org.celanwang.mio.ops.SchemaMigrations;
import org.springframework.context.annotation.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
@Configuration
public class SchemaConfig {
    @Bean SchemaMigrations schemaMigrations(JdbcTemplate jdbc,DataSource source){return new SchemaMigrations(jdbc,source);}
    @Bean @Order(Ordered.HIGHEST_PRECEDENCE) ApplicationRunner unifiedSchema(SchemaMigrations migrations){return args->migrations.migrate();}
}
