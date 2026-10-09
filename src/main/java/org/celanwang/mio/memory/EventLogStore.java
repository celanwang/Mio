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

/**
 * 事件日志（memory/events-YYYY-MM.jsonl）：执行链路事件的 append-only 持久化，按月滚动。
 * best-effort：写失败只 warn，绝不阻断主流程。
 */
@Component
public class EventLogStore {

    private static final Logger log = LoggerFactory.getLogger(EventLogStore.class);

    private final Path dir;
    private final ObjectMapper objectMapper;

    public EventLogStore(@Value("${app.memory.dir:./.mio}") String dir, ObjectMapper objectMapper) {
        this.dir = JsonFileSupport.expandHome(dir).resolve("memory");
        this.objectMapper = objectMapper;
    }

    public void append(String sessionId, TraceEvent event) {
        try {
            Files.createDirectories(dir);
            YearMonth month = YearMonth.from(
                    Instant.ofEpochMilli(event.timestamp()).atZone(ZoneId.systemDefault()));
            String line = objectMapper.writeValueAsString(
                    new EventLine(event.timestamp(), sessionId, event.node(), event.type(),
                            event.title(), event.detail()));
            Files.writeString(dir.resolve("events-" + month + ".jsonl"), line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("事件日志写入失败（不影响主流程）：{}", e.getMessage());
        }
    }

    /** 事件日志单行结构：TraceEvent 各字段 + sessionId。 */
    public record EventLine(long timestamp, String sessionId, String node, String type,
                            String title, String detail) {
    }
}
