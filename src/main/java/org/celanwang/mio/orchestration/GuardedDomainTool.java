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
    private final java.util.function.Supplier<CapabilityAvailability> availability;
    private final boolean declaredWrite;
    GuardedDomainTool(ToolBase delegate,TaskExecution execution,PolicyEngine policy){
        this(delegate,execution,policy,()->CapabilityAvailability.of(CapabilityAvailability.State.UNKNOWN,"NOT_CHECKED","未检查"));
    }
    GuardedDomainTool(ToolBase delegate,TaskExecution execution,PolicyEngine policy,java.util.function.Supplier<CapabilityAvailability> availability){
        this(delegate,execution,policy,availability,false);
    }
    GuardedDomainTool(ToolBase delegate,TaskExecution execution,PolicyEngine policy,java.util.function.Supplier<CapabilityAvailability> availability,boolean declaredWrite){
        super(ToolBase.builder().name(delegate.getName()).description(delegate.getDescription())
                .inputSchema(delegate.getParameters()).readOnly(false).concurrencySafe(false));
        this.delegate=delegate;this.execution=execution;this.policy=policy;
        this.availability=availability;
        this.declaredWrite=declaredWrite;
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
        var task=execution.businessActive();boolean read=!declaredWrite&&readOnly(delegate,input);
        availability.get().requireTool(getName());
        if(!read&&task.spec().scope()==TaskScope.PREPARE)return Mono.just(PermissionDecision.deny("当前任务只允许查询与准备"));
        if(!read&&(policy.isCircuitOpen()||!ToolPolicies.isJevReviewable(getName(),input)))return Mono.just(PermissionDecision.ask("本次操作必须人工确认"));
        if(read)return Mono.just(PermissionDecision.allow("任务范围内的查询或当前草稿编辑"));
        return delegate.checkPermissions(input,context);
    }
    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam call){
        return Mono.defer(()->{
            execution.businessActive();
            availability.get().requireTool(getName());
            execution.budget().tool();
            execution.validateApproval(call.getToolUseBlock(),ToolContract.hash(this));
            boolean read=!declaredWrite&&readOnly(delegate,call.getInput());
            if(!read)execution.verifyEffectConditions(call.getToolUseBlock());
            var observation=execution.observationStarting(call.getToolUseBlock());
            String operation=read?null:execution.beforeEffect(call.getToolUseBlock(),ToolContract.hash(this),delegate instanceof OperationBoundTool bound?bound.operationScope():"");
            // Persisting the operation can wait on database locks; recheck the lease and grant afterward.
            execution.businessActive();
            execution.validateApproval(call.getToolUseBlock(),ToolContract.hash(this));
            if(!read)execution.recheckEffectDeadline(call.getToolUseBlock());
            Mono<ToolResultBlock> invocation;
            if(operation!=null&&delegate instanceof OperationBoundTool bound){var op=execution.operation(operation);invocation=bound.callWithOperation(call,op.logicalId(),op.scopeKey());}
            else invocation=delegate.callAsync(call);
            return invocation.doOnNext(result->{
                if(operation!=null)execution.effectReturned(operation,output(result),result.getState().name());
                observation.ifPresent(o->execution.observationReturned(o,call.getToolUseBlock(),output(result),result.getState().name()));
            });
        });
    }
    private String output(ToolResultBlock block){return block.getOutput()==null?"":block.getOutput().stream()
            .filter(b->b instanceof TextBlock).map(b->((TextBlock)b).getText()).collect(java.util.stream.Collectors.joining("\n"));}
}
