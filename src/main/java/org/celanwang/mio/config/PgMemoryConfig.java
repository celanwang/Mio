package org.celanwang.mio.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PG 记忆存储配置（app.memory.store=pg 时生效）：连接池、JdbcTemplate 与启动自建表
 * （项目无 Flyway 惯例，CREATE TABLE IF NOT EXISTS 幂等自建）。
 * 失败方向 fail-open：PG 不可达不阻断启动，记忆链路 warn + 静默降级。
 */
@Configuration
@ConditionalOnProperty(name = "app.memory.store", havingValue = "pg")
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

    /** 启动自建表：DDL 与 docs/db/schema.sql 定稿保持一致（7 张表），embedding 模型 text-embedding-v4 维度 1024，向量列固定 vector(1024)。 */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    ApplicationRunner pgMemorySchemaInitializer(JdbcTemplate memoryJdbcTemplate) {
        return args -> {
            try {
                memoryJdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
                memoryJdbcTemplate.execute("""
                        CREATE TABLE IF NOT EXISTS events (
                            id         TEXT PRIMARY KEY,
                            ts         BIGINT NOT NULL,
                            session_id TEXT,
                            node       TEXT,
                            type       TEXT,
                            title      TEXT,
                            detail     TEXT
                        )
                        """);
                memoryJdbcTemplate.execute(
                        "CREATE INDEX IF NOT EXISTS idx_events_type_ts ON events(type, ts)");
                memoryJdbcTemplate.execute("""
                        CREATE TABLE IF NOT EXISTS messages (
                            id         TEXT PRIMARY KEY,
                            session_id TEXT,
                            ts         BIGINT NOT NULL,
                            role       TEXT NOT NULL,
                            text       TEXT NOT NULL
                        )
                        """);
                memoryJdbcTemplate.execute(
                        "CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id)");
                memoryJdbcTemplate.execute(
                        "CREATE INDEX IF NOT EXISTS idx_messages_ts ON messages(ts)");
                memoryJdbcTemplate.execute("""
                        CREATE TABLE IF NOT EXISTS embeddings (
                            id        TEXT PRIMARY KEY,
                            embedding vector(1024) NOT NULL
                        )
                        """);
                memoryJdbcTemplate.execute("""
                        CREATE TABLE IF NOT EXISTS entities (
                            id         BIGSERIAL PRIMARY KEY,
                            subject    TEXT NOT NULL,
                            predicate  TEXT NOT NULL,
                            object     TEXT NOT NULL,
                            valid_from BIGINT NOT NULL,
                            valid_to   BIGINT,
                            source_ts  BIGINT NOT NULL
                        )
                        """);
                memoryJdbcTemplate.execute("""
                        CREATE TABLE IF NOT EXISTS profiles (
                            id              TEXT PRIMARY KEY,
                            scope           TEXT NOT NULL,
                            title           TEXT NOT NULL,
                            source          TEXT NOT NULL,
                            confidence      DOUBLE PRECISION NOT NULL DEFAULT 0.5,
                            content         TEXT NOT NULL,
                            source_messages TEXT[],
                            created_at      BIGINT,
                            updated_at      BIGINT NOT NULL
                        )
                        """);
                memoryJdbcTemplate.execute(
                        "CREATE INDEX IF NOT EXISTS idx_profiles_scope ON profiles(scope)");
                memoryJdbcTemplate.execute("""
                        CREATE TABLE IF NOT EXISTS trusts (
                            tool_name        TEXT NOT NULL,
                            param_signature  TEXT NOT NULL,
                            approvals        INTEGER NOT NULL DEFAULT 0,
                            rejections       INTEGER NOT NULL DEFAULT 0,
                            last_decision_at BIGINT NOT NULL,
                            PRIMARY KEY (tool_name, param_signature)
                        )
                        """);
                memoryJdbcTemplate.execute("""
                        CREATE TABLE IF NOT EXISTS automations (
                            id           TEXT PRIMARY KEY,
                            title        TEXT NOT NULL,
                            tool_name    TEXT NOT NULL,
                            trigger_kind TEXT NOT NULL,
                            status       TEXT NOT NULL DEFAULT 'PROPOSED'
                                         CHECK (status IN ('PROPOSED', 'ACTIVE', 'REJECTED')),
                            reason       TEXT NOT NULL,
                            created_at   BIGINT NOT NULL,
                            decided_at   BIGINT
                        )
                        """);
                memoryJdbcTemplate.execute(
                        "CREATE INDEX IF NOT EXISTS idx_automations_status ON automations(status)");
                log.info("PG 记忆存储表结构就绪（events / messages / embeddings / entities / profiles / trusts / automations）");
            } catch (Exception e) {
                log.warn("PG 记忆存储建表失败（fail-open，记忆链路静默降级）：{}", e.getMessage());
            }
        };
    }
}
