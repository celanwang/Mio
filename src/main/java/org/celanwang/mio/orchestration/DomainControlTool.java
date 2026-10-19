package org.celanwang.mio.orchestration;

import io.agentscope.core.tool.*;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.*;
import reactor.core.publisher.Mono;
import java.util.*;

final class DomainControlTool extends ToolBase {
    private final TaskExecution execution;
    private final boolean question;
    DomainControlTool(TaskExecution execution,boolean question){
        super(ToolBase.builder().name(question?"mio_request_input":"mio_finish_task")
                .description(question?"缺少用户信息时提出一个明确补问并暂停任务。":"完成任务后提交准确结果；不得把未知业务结果说成成功。")
                .inputSchema(Map.of("type","object","properties",Map.of("text",Map.of("type","string","minLength",1)),"required",List.of("text"),"additionalProperties",false))
                .readOnly(false).concurrencySafe(false));this.execution=execution;this.question=question;
    }
    @Override public Mono<PermissionDecision> checkPermissions(Map<String,Object> input,PermissionContextState state){return Mono.just(PermissionDecision.allow("任务控制，不产生业务副作用"));}
    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam call){
        execution.active();execution.budget().tool();String text=(String)call.getInput().get("text");
        if(question)execution.requestInput(text);else execution.finish(text);
        return Mono.just(ToolResultBlock.text(text));
    }
}
