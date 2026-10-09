package org.celanwang.mio.trace;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 执行链路的内存存储：按会话保存事件，超出容量时淘汰最旧的会话和事件。
 */
@Component
public class TraceStore {

    private static final int MAX_SESSIONS = 50;
    private static final int MAX_EVENTS_PER_SESSION = 500;

    private final Map<String, CopyOnWriteArrayList<TraceEvent>> traces =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CopyOnWriteArrayList<TraceEvent>> eldest) {
                    return size() > MAX_SESSIONS;
                }
            });

    public void append(String sessionId, String type, String title, String detail) {
        CopyOnWriteArrayList<TraceEvent> events =
                traces.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        events.add(new TraceEvent(System.currentTimeMillis(), type, title, detail));
        while (events.size() > MAX_EVENTS_PER_SESSION) {
            events.remove(0);
        }
    }

    public List<TraceEvent> events(String sessionId) {
        List<TraceEvent> events = traces.get(sessionId);
        return events == null ? List.of() : List.copyOf(events);
    }

    /** 会话摘要，按最近活跃排序。 */
    public List<SessionSummary> sessions() {
        synchronized (traces) {
            List<SessionSummary> summaries = new ArrayList<>();
            for (Map.Entry<String, CopyOnWriteArrayList<TraceEvent>> entry : traces.entrySet()) {
                List<TraceEvent> events = entry.getValue();
                if (!events.isEmpty()) {
                    TraceEvent last = events.get(events.size() - 1);
                    summaries.add(new SessionSummary(
                            entry.getKey(), events.size(), last.timestamp(), firstUserText(events)));
                }
            }
            Collections.reverse(summaries);
            return summaries;
        }
    }

    private String firstUserText(List<TraceEvent> events) {
        for (TraceEvent event : events) {
            if ("user".equals(event.type())) {
                String text = event.detail();
                return text.length() > 30 ? text.substring(0, 30) + "…" : text;
            }
        }
        return "";
    }

    public record SessionSummary(String sessionId, int eventCount, long lastActive, String firstUserMessage) {
    }
}
