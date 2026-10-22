package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.*;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.*;
import reactor.core.publisher.Mono;
import java.util.*;

/** The supervisor can manage tasks but has no raw business tools or approval-signing tool. */
final class OrchestrationTool extends ToolBase {
    private final String operation,session;
    private final TaskRuntime runtime;private final AgentRegistry registry;private final RunBudget budget;private final ObjectMapper json;
    private final JdbcGoalAcceptance acceptance;
    OrchestrationTool(String operation,String session,TaskRuntime runtime,AgentRegistry registry,RunBudget budget,ObjectMapper json){
        this(operation,session,runtime,registry,budget,json,null);
    }
    OrchestrationTool(String operation,String session,TaskRuntime runtime,AgentRegistry registry,RunBudget budget,ObjectMapper json,JdbcGoalAcceptance acceptance){
        super(ToolBase.builder().name(operation).description(description(operation)).inputSchema(schema(operation)).readOnly(false).concurrencySafe(false));
        this.operation=operation;this.session=session;this.runtime=runtime;this.registry=registry;this.budget=budget;this.json=json;
        this.acceptance=acceptance;
    }
    private static String description(String name){return switch(name){
        case "discover_agents"->"发现已注册能力及本地可用性状态。UNAVAILABLE 不委派；PREPARATION_REQUIRED 仅先准备；UNKNOWN 不能声称业务就绪。发现过程不查询真实业务。";
        case "delegate_task"->"按用户目标创建受限任务。key 在目标版本内幂等；PREPARE 仅查询，REQUEST_APPROVAL 仍经审批。dependencies 是前置任务 id；inputs 用来源任务 facts 中的 factId/version 绑定事实，不能编造事实值。";
        case "review_plan"->"检查当前目标依赖失效范围，返回需刷新来源、需重规划任务和保留任务。只分析已声明依赖，不执行或重试业务动作。";
        case "read_goal_acceptance"->"读取用户明确确认的目标验收范围及当前证据评估；不能确认或修改条件，不产生业务授权。仅 SATISFIED 表示确认范围当前满足。";
        case "list_tasks"->"读取当前会话全部任务的真实进度和结果；结果不明不得声称成功。";
        case "run_queued_task"->"领取并运行指定准备队列任务；只在前台当前目标版本内执行，不重放已经启动的任务。前置依赖和事实必须有效。";
        case "continue_task"->"恢复等待依赖的任务，或使用用户最新原话回答该任务的补问。不能用于批准、重试失败或结果不明的操作。";
        case "cancel_task"->"停止任务的后续步骤；已发出的业务请求仍需核查，不自动撤销订单。";
        case "reconcile_operation"->"只核查已有操作的关联回执及业务查询，不重新提交。taskId/operationId 必须来自当前会话任务记录；无关联证据时保持未知。";
        default->throw new IllegalArgumentException(name);
    };}
    private static Map<String,Object> schema(String name){
        if(name.equals("reconcile_operation"))return Map.of("type","object","properties",Map.of("taskId",Map.of("type","string"),"operationId",Map.of("type","string")),"required",List.of("taskId","operationId"),"additionalProperties",false);
        if(name.equals("delegate_task"))return Map.of("type","object","properties",Map.of(
                "key",Map.of("type","string"),"capabilityId",Map.of("type","string"),"objective",Map.of("type","string"),
                "constraints",Map.of("type","array","items",Map.of("type","string")),
                "dependencies",Map.of("type","array","items",Map.of("type","string")),
                "inputs",Map.of("type","array","maxItems",8,"items",Map.of("type","object","properties",Map.of(
                        "taskId",Map.of("type","string"),"factId",Map.of("type","string"),"version",Map.of("type","string"),"alias",Map.of("type","string")),
                        "required",List.of("taskId","factId","version","alias"),"additionalProperties",false)),
                "inputContract",Map.of("type","string","description","选择 capability.inputContracts 中的契约编号；按 requiredFacts 的别名和类型完整绑定 inputs。"),
                "outputContract",Map.of("type","string","description","选择 capability.outputContracts 中的契约编号；需要可信报价选 ride-quote，需要已核实创建结果选 resource-reference。report 只表示工作报告。"),
                "dispatch",Map.of("type","string","enum",List.of("INLINE","QUEUED"),"description","默认 INLINE；QUEUED 仅用于 PREPARE，只入队，不立即执行。"),
                "scope",Map.of("type","string","enum",List.of("PREPARE","REQUEST_APPROVAL"))),
                "required",List.of("key","capabilityId","objective"),"additionalProperties",false);
        if(name.equals("continue_task")||name.equals("cancel_task")||name.equals("run_queued_task"))return Map.of("type","object","properties",Map.of("taskId",Map.of("type","string")),"required",List.of("taskId"),"additionalProperties",false);
        return Map.of("type","object","properties",Map.of(),"additionalProperties",false);
    }
    @Override public Mono<PermissionDecision> checkPermissions(Map<String,Object> input,PermissionContextState state){return Mono.just(PermissionDecision.allow("受控任务管理；不批准任何业务操作"));}
    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam call){
        return Mono.defer(()->{
            budget.tool();Map<String,Object> input=call.getInput();
            Mono<?> result=switch(operation){
                case "review_plan"->Mono.just(budget.fenced(()->runtime.reviewPlan(session)));
                case "read_goal_acceptance"->Mono.just(budget.fenced(()->acceptance.snapshot(session).acceptance()));
                case "discover_agents"->Mono.just(registry.discover());
                case "list_tasks"->Mono.just(budget.fenced(()->runtime.list(session).stream().map(runtime::summary).toList()));
                case "delegate_task"->runtime.submit(session,json.convertValue(input,TaskSpec.class),budget).map(runtime::summary);
                case "run_queued_task"->runtime.runQueuedTask(session,(String)input.get("taskId"),budget).map(runtime::summary);
                case "continue_task"->runtime.continueTask(session,(String)input.get("taskId"),budget).map(runtime::summary);
                case "cancel_task"->Mono.fromSupplier(()->budget.fenced(()->runtime.summary(runtime.cancel(session,(String)input.get("taskId")))));
                case "reconcile_operation"->runtime.reconcile(session,(String)input.get("taskId"),(String)input.get("operationId"),budget).map(runtime::summary);
                default->Mono.error(new IllegalArgumentException("未知任务管理操作"));
            };
            return result.map(value->{try{return ToolResultBlock.text(json.writeValueAsString(value));}catch(Exception e){throw new IllegalStateException("结果编码失败",e);}});
        });
    }
}
