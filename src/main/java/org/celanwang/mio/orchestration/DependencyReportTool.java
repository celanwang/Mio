package org.celanwang.mio.orchestration;
import io.agentscope.core.tool.*;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.*;
import reactor.core.publisher.Mono;
import java.util.*;
final class DependencyReportTool extends ToolBase {
    private final TaskExecution execution;
    DependencyReportTool(TaskExecution execution){super(ToolBase.builder().name("mio_read_dependency_report").description("读取本任务前置步骤的工作报告。报告不是可信业务事实，不得用其替代事实输入契约。")
        .inputSchema(Map.of("type","object","properties",Map.of("taskId",Map.of("type","string")),"required",List.of("taskId"),"additionalProperties",false)).readOnly(true).concurrencySafe(true));this.execution=execution;}
    @Override public Mono<PermissionDecision> checkPermissions(Map<String,Object> input,PermissionContextState state){return Mono.just(PermissionDecision.allow("读取已声明的前置工作报告"));}
    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam call){return Mono.fromCallable(()->ToolResultBlock.text(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(execution.dependencyReport((String)call.getInput().get("taskId")))));}
}
