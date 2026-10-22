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
    private GoalBudgetStore ledger;
    private String goalId;
    private final AtomicInteger pending = new AtomicInteger();
    public RunBudget(Limits limits, Clock clock) {
        this.limits=limits;this.clock=clock;deadline=Math.addExact(clock.millis(),limits.millis());
    }
    public synchronized void bind(GoalBudgetStore ledger,String goalId,GoalBudgetStore.Limits limits) {
        bind(ledger,goalId,limits,false);
    }
    public synchronized void bind(GoalBudgetStore ledger,String goalId,GoalBudgetStore.Limits limits,boolean historyUnknown){
        if(this.ledger!=null||models.get()!=0||tools.get()!=0||tasks.get()!=0)throw new IllegalStateException("预算必须在执行前绑定一次");
        ledger.open(goalId,limits,historyUnknown);this.ledger=ledger;this.goalId=goalId;
    }
    public void active() { if (clock.millis() >= deadline) throw new BudgetExceeded("本轮执行时间预算已耗尽"); }
    private void consume(AtomicInteger counter,int maximum,String name,GoalBudgetStore.Kind kind) {
        active();counter.updateAndGet(value->{if(value>=maximum)throw new BudgetExceeded(name+"本轮预算已耗尽");return value+1;});
        if(ledger!=null)ledger.reserve(goalId,kind);
    }
    public ModelCall beginModel(int contextChars) {
        if(contextChars<0||contextChars>limits.contextCharacters())throw new BudgetExceeded("模型上下文预算超限");
        active();models.updateAndGet(value->{if(value>=limits.modelCalls())throw new BudgetExceeded("本轮模型调用预算已耗尽");return value+1;});
        String id=ledger==null?null:ledger.reserve(goalId,GoalBudgetStore.Kind.MODEL);
        pending.incrementAndGet();return new ModelCall(id);
    }
    /** Review services do not expose usage; reserve and explicitly settle as unknown. */
    public void model(int contextChars){beginModel(contextChars).finish(null,false);}
    public void tool(){consume(tools,limits.toolCalls(),"工具调用",GoalBudgetStore.Kind.TOOL);}
    public void task(){consume(tasks,limits.taskCalls(),"任务恢复",GoalBudgetStore.Kind.TASK);}
    public synchronized void tokens(Long count){
        if(count==null){tokenUsageComplete=false;return;}
        if(count<0)throw new IllegalArgumentException("token 用量无效");
        long total=Math.addExact(tokens.get(),count);tokens.set(total);
        if(total>limits.tokens())throw new BudgetExceeded("本轮已报告 token 预算已耗尽");
    }
    public final class ModelCall {
        private final String id;private boolean settled;
        private ModelCall(String id){this.id=id;}
        public synchronized void finish(Long used,boolean complete){
            if(settled)return;
            if(used!=null&&(used<0||used>2L*Integer.MAX_VALUE)||complete&&used==null)throw new IllegalArgumentException("模型用量无效");
            if(ledger!=null)ledger.settle(goalId,id,used,complete);
            settled=true;pending.decrementAndGet();if(!complete)tokenUsageComplete=false;
            tokens(used);
            if(ledger!=null){var state=ledger.find(goalId).orElseThrow();if(state.knownTokens()>state.limits().tokens())throw new BudgetExceeded("本目标已报告 token 预算已耗尽");}
        }
        public synchronized void unfinished(){if(!settled)tokenUsageComplete=false;}
    }
    public Usage usage(){return new Usage(models.get(),tools.get(),tasks.get(),tokens.get(),tokenUsageComplete&&pending.get()==0);}
    public static final class BudgetExceeded extends IllegalStateException {
        public BudgetExceeded(String message){super(message);}
    }
}
