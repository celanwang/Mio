package org.celanwang.mio.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PG 记忆存储配置（默认装配，PG 是唯一记忆后端）：连接池、JdbcTemplate 与启动自建表
 * （项目无 Flyway 惯例，CREATE TABLE IF NOT EXISTS 幂等自建）。
 * 失败方向 fail-open：PG 不可达不阻断启动，记忆链路 warn + 静默降级。
 */
@Configuration
public class PgMemoryConfig {

    private static final Logger log = LoggerFactory.getLogger(PgMemoryConfig.class);

    @Bean(destroyMethod = "close")
    DataSource memoryDataSource(@Value("${app.memory.pg.url:}") String url,
                                @Value("${app.memory.pg.username:}") String username,
                                @Value("${app.memory.pg.password:}") String password) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("mio-memory-pg");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(4);
        // 连接失败快速返回，由调用方 fail-open。
        config.setConnectionTimeout(5_000);
        // PG 不可达时不阻断启动：池初始化失败转后台重试。
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    @Bean
    JdbcTemplate memoryJdbcTemplate(DataSource memoryDataSource) {
        return new JdbcTemplate(memoryDataSource);
    }

    // Production schema initialization is owned by SchemaConfig and versioned SQL resources.
}
