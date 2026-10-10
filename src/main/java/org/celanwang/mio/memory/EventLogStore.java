package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.trace.TraceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 事件日志（memory/events-YYYY-MM.jsonl）：执行链路事件的 append-only 持久化，按月滚动。
 * {@link EventPublisher} 的本地实现；best-effort：写失败只 warn，绝不阻断主流程。
 */
@Component
public class EventLogStore implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventLogStore.class);

    private final Path dir;
    private final ObjectMapper objectMapper;

    public EventLogStore(@Value("${app.memory.dir:./.mio}") String dir, ObjectMapper objectMapper) {
        this.dir = JsonFileSupport.expandHome(dir).resolve("memory");
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(String sessionId, TraceEvent event) {
        try {
            Files.createDirectories(dir);
            YearMonth month = YearMonth.from(
                    Instant.ofEpochMilli(event.timestamp()).atZone(ZoneId.systemDefault()));
            String line = objectMapper.writeValueAsString(
                    new EventLine(event.timestamp(), sessionId, event.node(), event.type(),
                            event.title(), event.detail(), UUID.randomUUID().toString()));
            Files.writeString(dir.resolve("events-" + month + ".jsonl"), line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("事件日志写入失败（不影响主流程）：{}", e.getMessage());
        }
    }

    /**
     * 事件日志单行结构：TraceEvent 各字段 + sessionId + eventId。
     * schema 规范：timestamp/sessionId/node/type/title/detail/eventId 为保留字段，
     * 只允许追加新字段，不允许改名或变更语义（保证未来上 MQ 后历史事件可重放）；
     * eventId 为幂等去重键，历史行没有该字段时按 null 容忍。
     */
    public record EventLine(long timestamp, String sessionId, String node, String type,
                            String title, String detail, String eventId) {
    }
}
