package org.celanwang.mio.memory;

import org.celanwang.mio.trace.TraceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@link EventPublisher} 的 PG 实现（app.memory.store=pg 时启用）：事件落 events 表，
 * eventId 作主键幂等去重。best-effort：写失败只 warn，绝不阻断主流程。
 */
@Component
@ConditionalOnProperty(name = "app.memory.store", havingValue = "pg")
public class PgEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PgEventPublisher.class);

    private final JdbcTemplate jdbc;

    public PgEventPublisher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void publish(String sessionId, TraceEvent event) {
        try {
            jdbc.update("""
                    INSERT INTO events(id, ts, session_id, node, type, title, detail)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """, UUID.randomUUID().toString(), event.timestamp(), sessionId,
                    event.node(), event.type(), event.title(), event.detail());
        } catch (Exception e) {
            log.warn("事件日志写入失败（不影响主流程）：{}", e.getMessage());
        }
    }
}
