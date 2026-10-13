package org.celanwang.mio.memory;

import org.celanwang.mio.config.ToolPolicies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 习惯检测器（统计式，不用 LLM）：扫描 events 表中的 tool_call 事件，
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

    private final JdbcTemplate jdbc;
    private final AutomationStore automationStore;
    private final MemoryTaskExecutor taskExecutor;
    private final boolean enabled;
    private final AtomicLong lastRunAt = new AtomicLong();

    public HabitDetector(@Value("${app.memory.habit-detector.enabled:true}") boolean enabled,
                         AutomationStore automationStore,
                         MemoryTaskExecutor taskExecutor,
                         JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.automationStore = automationStore;
        this.taskExecutor = taskExecutor;
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
        taskExecutor.execute(() -> {
            try {
                detect();
            } catch (Exception e) {
                log.warn("习惯检测失败（静默降级）：{}", e.getMessage());
            }
        });
    }

    /** 查询当月及上月的事件日志，统计各工具的调用次数与分布日期，对候选习惯生成提议。 */
    private void detect() {
        Map<String, Integer> calls = new HashMap<>();
        Map<String, Set<LocalDate>> days = new HashMap<>();
        long since = YearMonth.now().minusMonths(1).atDay(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        jdbc.query("SELECT node, ts FROM events WHERE type = 'tool_call' AND ts >= ?",
                rs -> {
                    String node = rs.getString("node");
                    if (node == null || !node.startsWith("tool:")) {
                        return;
                    }
                    String tool = node.substring("tool:".length());
                    calls.merge(tool, 1, Integer::sum);
                    days.computeIfAbsent(tool, k -> new HashSet<>()).add(
                            Instant.ofEpochMilli(rs.getLong("ts"))
                                    .atZone(ZoneId.systemDefault()).toLocalDate());
                }, since);
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
}
