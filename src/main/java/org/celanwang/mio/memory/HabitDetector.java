package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.config.ToolPolicies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 习惯检测器（统计式，不用 LLM）：扫描事件日志中的 tool_call 事件，
 * 调用次数 ≥3 且分布在 ≥2 个不同日期的白名单工具 → 生成自动化提议（待用户批准）。
 * 每轮对话后异步触发，1 小时内不重复检测；检测失败静默降级。
 */
@Component
public class HabitDetector {

    private static final Logger log = LoggerFactory.getLogger(HabitDetector.class);

    private static final int MIN_CALLS = 3;
    private static final int MIN_DAYS = 2;
    private static final long THROTTLE_MILLIS = 3600_000;

    /** 白名单工具的提议文案模板（中文、说明会做什么）。 */
    private static final Map<String, String> PROPOSAL_TITLES = Map.of(
            "auto-bind-coupons", "自动帮你领取可用优惠券",
            "draw-lottery", "自动帮你参与抽奖",
            "query-my-coupons", "会话开始时自动查看你的优惠券",
            "query-promotions", "会话开始时自动查看当前优惠活动");

    private final Path eventsDir;
    private final ObjectMapper objectMapper;
    private final AutomationStore automationStore;
    private final boolean enabled;
    private final AtomicLong lastRunAt = new AtomicLong();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "habit-detector");
        thread.setDaemon(true);
        return thread;
    });

    public HabitDetector(@Value("${app.memory.dir:./.mio}") String dir,
                         @Value("${app.memory.habit-detector.enabled:true}") boolean enabled,
                         AutomationStore automationStore,
                         ObjectMapper objectMapper) {
        this.eventsDir = JsonFileSupport.expandHome(dir).resolve("memory");
        this.enabled = enabled;
        this.automationStore = automationStore;
        this.objectMapper = objectMapper;
    }

    /** 异步触发一次检测；开关关闭或距上次检测不足 1 小时则跳过。 */
    public void detectAsync() {
        if (!enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRunAt.get() < THROTTLE_MILLIS || !lastRunAt.compareAndSet(lastRunAt.get(), now)) {
            return;
        }
        executor.execute(() -> {
            try {
                detect();
            } catch (Exception e) {
                log.warn("习惯检测失败（静默降级）：{}", e.getMessage());
            }
        });
    }

    /** 扫描当月及上月事件日志，统计各工具的调用次数与分布日期，对候选习惯生成提议。 */
    private void detect() throws Exception {
        Map<String, Integer> calls = new HashMap<>();
        Map<String, Set<LocalDate>> days = new HashMap<>();
        YearMonth month = YearMonth.now();
        scan(month, calls, days);
        scan(month.minusMonths(1), calls, days);
        for (Map.Entry<String, Integer> entry : calls.entrySet()) {
            String tool = entry.getKey();
            if (!ToolPolicies.AUTOMATABLE.contains(tool)
                    || entry.getValue() < MIN_CALLS
                    || days.getOrDefault(tool, Set.of()).size() < MIN_DAYS) {
                continue;
            }
            String reason = "近期你有 " + days.get(tool).size() + " 天使用了「" + tool + "」（共 "
                    + entry.getValue() + " 次）";
            AutomationRule rule = automationStore.propose(tool, PROPOSAL_TITLES.get(tool), reason);
            if (rule != null) {
                log.info("习惯检测生成自动化提议：{}（{}）", rule.title(), rule.reason());
            }
        }
    }

    private void scan(YearMonth month, Map<String, Integer> calls, Map<String, Set<LocalDate>> days)
            throws Exception {
        Path file = eventsDir.resolve("events-" + month + ".jsonl");
        if (!Files.exists(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                EventLogStore.EventLine event;
                try {
                    event = objectMapper.readValue(line, EventLogStore.EventLine.class);
                } catch (Exception e) {
                    continue; // 单行损坏跳过，不影响整体检测
                }
                if (!"tool_call".equals(event.type()) || event.node() == null
                        || !event.node().startsWith("tool:")) {
                    continue;
                }
                String tool = event.node().substring("tool:".length());
                calls.merge(tool, 1, Integer::sum);
                days.computeIfAbsent(tool, k -> new HashSet<>()).add(
                        Instant.ofEpochMilli(event.timestamp()).atZone(ZoneId.systemDefault()).toLocalDate());
            }
        }
    }
}
