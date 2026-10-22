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
    OrchestrationTool(String operation,String session,TaskRuntime runtime,AgentRegistry registry,RunBudget budget,ObjectMapper json){
        super(ToolBase.builder().name(operation).description(description(operation)).inputSchema(schema(operation)).readOnly(false).concurrencySafe(false));
        this.operation=operation;this.session=session;this.runtime=runtime;this.registry=registry;this.budget=budget;this.json=json;
    }
    private static String description(String name){return switch(name){
        case "discover_agents"->"发现已注册能力及本地可用性状态。UNAVAILABLE 不委派；PREPARATION_REQUIRED 仅先准备；UNKNOWN 不能声称业务就绪。发现过程不查询真实业务。";
        case "delegate_task"->"按用户目标创建受限任务。key 在目标版本内幂等；PREPARE 仅查询，REQUEST_APPROVAL 仍经审批。dependencies 是前置任务 id；inputs 用来源任务 facts 中的 factId/version 绑定事实，不能编造事实值。";
        case "list_tasks"->"读取当前会话全部任务的真实进度和结果；结果不明不得声称成功。";
        case "continue_task"->"恢复等待依赖的任务，或使用用户最新原话回答该任务的补问。不能用于批准、重试失败或结果不明的操作。";
        case "cancel_task"->"停止任务的后续步骤；已发出的业务请求仍需核查，不自动撤销订单。";
        default->throw new IllegalArgumentException(name);
    };}
    private static Map<String,Object> schema(String name){
        if(name.equals("delegate_task"))return Map.of("type","object","properties",Map.of(
                "key",Map.of("type","string"),"capabilityId",Map.of("type","string"),"objective",Map.of("type","string"),
                "constraints",Map.of("type","array","items",Map.of("type","string")),
                "dependencies",Map.of("type","array","items",Map.of("type","string")),
                "inputs",Map.of("type","array","maxItems",8,"items",Map.of("type","object","properties",Map.of(
                        "taskId",Map.of("type","string"),"factId",Map.of("type","string"),"version",Map.of("type","string"),"alias",Map.of("type","string")),
                        "required",List.of("taskId","factId","version","alias"),"additionalProperties",false)),
                "scope",Map.of("type","string","enum",List.of("PREPARE","REQUEST_APPROVAL"))),
                "required",List.of("key","capabilityId","objective"),"additionalProperties",false);
        if(name.equals("continue_task")||name.equals("cancel_task"))return Map.of("type","object","properties",Map.of("taskId",Map.of("type","string")),"required",List.of("taskId"),"additionalProperties",false);
        return Map.of("type","object","properties",Map.of(),"additionalProperties",false);
    }
    @Override public Mono<PermissionDecision> checkPermissions(Map<String,Object> input,PermissionContextState state){return Mono.just(PermissionDecision.allow("受控任务管理；不批准任何业务操作"));}
    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam call){
        return Mono.defer(()->{
            budget.tool();Map<String,Object> input=call.getInput();
            Mono<?> result=switch(operation){
                case "discover_agents"->Mono.just(registry.discover());
                case "list_tasks"->Mono.just(runtime.list(session).stream().map(t->TaskSummary.of(t,registry)).toList());
                case "delegate_task"->runtime.submit(session,json.convertValue(input,TaskSpec.class),budget).map(t->TaskSummary.of(t,registry));
                case "continue_task"->runtime.continueTask(session,(String)input.get("taskId"),budget).map(t->TaskSummary.of(t,registry));
                case "cancel_task"->Mono.fromSupplier(()->TaskSummary.of(runtime.cancel(session,(String)input.get("taskId")),registry));
                default->Mono.error(new IllegalArgumentException("未知任务管理操作"));
            };
            return result.map(value->{try{return ToolResultBlock.text(json.writeValueAsString(value));}catch(Exception e){throw new IllegalStateException("结果编码失败",e);}});
        });
    }
}
