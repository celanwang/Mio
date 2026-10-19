package org.celanwang.mio.orchestration;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** One shared budget per foreground run, including supervisor, workers and Jev resumptions. */
public final class RunBudget {
    public record Limits(int modelCalls, int toolCalls, int taskCalls, long tokens, int contextCharacters, long millis) {
        public Limits {
            if (modelCalls < 1 || toolCalls < 1 || taskCalls < 1 || tokens < 1 || contextCharacters < 1 || millis < 1)
                throw new IllegalArgumentException("预算必须为正数");
        }
    }
    public record Usage(int modelCalls, int toolCalls, int taskCalls, long tokens, boolean tokenUsageComplete) {}
    private final Limits limits;
    private final Clock clock;
    private final long deadline;
    private final AtomicInteger models = new AtomicInteger(), tools = new AtomicInteger(), tasks = new AtomicInteger();
    private final AtomicLong tokens = new AtomicLong();
    private volatile boolean tokenUsageComplete = true;
    public RunBudget(Limits limits, Clock clock) {
        this.limits=limits;this.clock=clock;deadline=Math.addExact(clock.millis(),limits.millis());
    }
    public void active() { if (clock.millis() >= deadline) throw new BudgetExceeded("执行时间预算已耗尽"); }
    private void consume(AtomicInteger counter, int maximum, String name) {
        active(); if (counter.incrementAndGet() > maximum) throw new BudgetExceeded(name+"预算已耗尽");
    }
    public void model(int contextChars) {
        if (contextChars > limits.contextCharacters()) throw new BudgetExceeded("模型上下文过大");
        consume(models,limits.modelCalls(),"模型/审核调用");
    }
    public void tool() { consume(tools,limits.toolCalls(),"工具调用"); }
    public void task() { consume(tasks,limits.taskCalls(),"子任务恢复"); }
    public void tokens(Long count) {
        if (count == null) { tokenUsageComplete=false; return; }
        if (count < 0 || tokens.addAndGet(count) > limits.tokens()) throw new BudgetExceeded("模型 token 预算已耗尽");
    }
    public Usage usage() { return new Usage(models.get(),tools.get(),tasks.get(),tokens.get(),tokenUsageComplete); }
    public static final class BudgetExceeded extends IllegalStateException {
        public BudgetExceeded(String message) { super(message); }
    }
}
