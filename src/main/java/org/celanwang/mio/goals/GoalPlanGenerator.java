package org.celanwang.mio.goals;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import org.celanwang.mio.orchestration.*;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;

/** Bounded, tool-free planning. The model can only create an unadopted proposal. */
public final class GoalPlanGenerator {
    private final Model model;private final AgentRegistry registry;private final TaskRuntime runtime;private final JdbcGoalPrograms programs;private final ExecutionLeaseStore leases;private final GoalBudgetStore budgets;private final ObjectMapper json;
    public GoalPlanGenerator(Model model,AgentRegistry registry,TaskRuntime runtime,JdbcGoalPrograms programs,ExecutionLeaseStore leases,GoalBudgetStore budgets,ObjectMapper json){this.model=model;this.registry=registry;this.runtime=runtime;this.programs=programs;this.leases=leases;this.budgets=budgets;this.json=json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);}
    private GoalBudgetStore.Limits goalLimits=new GoalBudgetStore.Limits(160,320,48,320000);
    public GoalPlanGenerator withLimits(GoalBudgetStore.Limits limits){this.goalLimits=limits;return this;}
    public Mono<Void> generate(String session){return Mono.fromRunnable(()->{
        var token=leases.acquire(session,50000);
        try{
            var goal=runtime.goal(session);goal.requireActive();var budget=new RunBudget(new RunBudget.Limits(1,1,1,12000,100000,40000),Clock.systemUTC());budget.bindLease(leases,token);budget.bind(budgets,goal.id(),goalLimits,true);
            budget.bindGoalGuard(()->{var current=runtime.goal(session);current.requireActive();if(current.revision()!=goal.revision()||current.controlRevision()!=goal.controlRevision())throw new GoalRecord.Stopped();});
            String instruction="仅输出 JSON 对象，含 fields 字符串字典和 steps 数组。步骤字段 id,capabilityId,objective,fields,dependencies,outputContract,checks,inputContract,inputs。只计划 PREPARE，不执行工具，不添加用户未要求的业务。使用已注册能力和契约；未知必要信息在 objective 要求领域助手补问。依赖按步骤编号；事实 inputs 使用 别名:前置步骤/事实key。没有事实时选 default 输入契约和 report 输出。最多8步，字段最多10项。不要输出 markdown。已注册能力："+json.writeValueAsString(registry.discover());
            var chunks=new BudgetedModel(model,budget).stream(List.of(Msg.builder().role(MsgRole.SYSTEM).content(TextBlock.builder().text(instruction).build()).build(),new UserMessage(goal.trustedIntent())),List.of(),GenerateOptions.builder().maxTokens(4096).build()).collectList().block(Duration.ofSeconds(40));
            String text=chunks.stream().flatMap(c->c.getContent().stream()).filter(b->b instanceof TextBlock).map(b->((TextBlock)b).getText()).collect(java.util.stream.Collectors.joining()).strip();
            if(text.length()>40000)throw new IllegalArgumentException("计划响应过大");var node=json.readTree(text);if(!node.isObject()||!node.path("fields").isObject()||!node.path("steps").isArray())throw new IllegalArgumentException("计划响应格式无效");
            var fields=json.convertValue(node.get("fields"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){});var steps=json.convertValue(node.get("steps"),new com.fasterxml.jackson.core.type.TypeReference<List<GoalProgram.Step>>(){});var caps=new HashSet<String>();steps.forEach(s->caps.add(s.capabilityId()));
            var previous=programs.find(goal.id()).orElse(null);var policy=previous==null?new GoalProgram.Policy(false,System.currentTimeMillis()+3600000,10,300,1,caps):new GoalProgram.Policy(false,Math.max(System.currentTimeMillis()+60000,previous.policy().until()),previous.policy().maxActivations(),previous.policy().intervalSeconds(),previous.policy().parallelism(),caps,previous.policy().trackResources());
            budget.fenced(()->{programs.propose(session,new GoalProgram.Request(previous==null?0:previous.revision(),goal.revision(),fields,steps,policy));return null;});
        }catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("无法生成有效计划，请重试或手动编辑",e);}finally{leases.release(token);}
    }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).then();}
}
