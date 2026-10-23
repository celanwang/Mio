package org.celanwang.mio.ops;
import org.celanwang.mio.orchestration.*;
import org.springframework.core.env.Environment;
/** One configuration source for interactive, background and planning runs. */
public final class ExecutionSettings {
    private final Environment env;public ExecutionSettings(Environment env){this.env=env;}
    public RunBudget.Limits foreground(){return limits(895000,40,80,12,80000,100000);}
    public RunBudget.Limits background(){return limits(120000,40,80,12,80000,100000);}
    public RunBudget.Limits planning(){return limits(40000,1,1,1,12000,100000);}
    private RunBudget.Limits limits(long duration,int models,int tools,int tasks,long tokens,int context){return new RunBudget.Limits(Math.min(models,env.getProperty("app.multi-agent.max-model-calls",Integer.class,40)),Math.min(tools,env.getProperty("app.multi-agent.max-tool-calls",Integer.class,80)),Math.min(tasks,env.getProperty("app.multi-agent.max-task-calls",Integer.class,12)),Math.min(tokens,env.getProperty("app.multi-agent.max-tokens",Long.class,80000L)),Math.min(context,env.getProperty("app.multi-agent.max-context-characters",Integer.class,100000)),Math.min(duration,env.getProperty("app.multi-agent.timeout-ms",Long.class,150000L)));}
    public GoalBudgetStore.Limits goal(){return new GoalBudgetStore.Limits(env.getProperty("app.multi-agent.goal-max-model-calls",Integer.class,160),env.getProperty("app.multi-agent.goal-max-tool-calls",Integer.class,320),env.getProperty("app.multi-agent.goal-max-task-calls",Integer.class,48),env.getProperty("app.multi-agent.goal-max-tokens",Long.class,320000L));}
}
