package org.celanwang.mio.orchestration;

import io.agentscope.core.message.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.tool.*;
import org.celanwang.mio.config.ToolPolicies;
import org.celanwang.mio.security.*;
import reactor.core.publisher.Mono;
import java.util.*;

/** Every domain tool, including dynamically discovered tools, passes this execution boundary. */
final class GuardedDomainTool extends ToolBase implements ToolContract.SourceBound {
    private final ToolBase delegate;
    private final TaskExecution execution;
    private final PolicyEngine policy;
    GuardedDomainTool(ToolBase delegate,TaskExecution execution,PolicyEngine policy){
        super(ToolBase.builder().name(delegate.getName()).description(delegate.getDescription())
                .inputSchema(delegate.getParameters()).readOnly(false).concurrencySafe(false));
        this.delegate=delegate;this.execution=execution;this.policy=policy;
    }
    @Override public AgentTool contractSource(){return delegate;}
    static boolean readOnly(ToolBase tool,Map<String,Object> input){
        if(ToolPolicies.HUMAN_ONLY.contains(tool.getName()))return false;
        if(tool.getName().equals("tencent_ride"))return Set.of("preflight","get-resident-city","addr-keys","addr-get-value",
                "short-cut-keys","short-cut-get-value","poi-search","select-poi","estimate-price","query-ongoing-order",
                "query-order","check-cancel-fee","query-driver-location","build-static-map-url","addr-sync-to-state").contains(input.get("command"));
        return ToolPolicies.READ_ONLY.contains(tool.getName()) || tool.isReadOnly();
    }
    @Override public Mono<PermissionDecision> checkPermissions(Map<String,Object> input,PermissionContextState context){
        var task=execution.businessActive();boolean read=readOnly(delegate,input);
        if(!read&&task.spec().scope()==TaskScope.PREPARE)return Mono.just(PermissionDecision.deny("当前任务只允许查询与准备"));
        if(!read&&(policy.isCircuitOpen()||!ToolPolicies.isJevReviewable(getName(),input)))return Mono.just(PermissionDecision.ask("本次操作必须人工确认"));
        if(read)return Mono.just(PermissionDecision.allow("任务范围内的查询或当前草稿编辑"));
        return delegate.checkPermissions(input,context);
    }
    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam call){
        return Mono.defer(()->{
            execution.businessActive();execution.budget().tool();
            execution.validateApproval(call.getToolUseBlock(),ToolContract.hash(this));
            boolean read=readOnly(delegate,call.getInput());
            String operation=read?null:execution.beforeEffect(call.getToolUseBlock(),ToolContract.hash(this));
            return delegate.callAsync(call).doOnNext(result->{
                if(operation!=null)execution.effectReturned(operation,output(result),result.getState().name());
            });
        });
    }
    private String output(ToolResultBlock block){return block.getOutput()==null?"":block.getOutput().stream()
            .filter(b->b instanceof TextBlock).map(b->((TextBlock)b).getText()).collect(java.util.stream.Collectors.joining("\n"));}
}
