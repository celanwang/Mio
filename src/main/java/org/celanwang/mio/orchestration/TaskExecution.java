package org.celanwang.mio.orchestration;

import io.agentscope.core.message.ToolUseBlock;
import org.celanwang.mio.security.PolicyEngine;
import java.time.Clock;
import java.util.*;

/** A capability lease held by trusted runtime code, never reconstructed from model-generated text. */
public final class TaskExecution {
    private final CoordinationStore store;
    private final String taskId;
    private final GoalRecord goal;
    private final RunBudget budget;
    private final PolicyEngine policy;
    private final Clock clock;
    public record ApprovalGrant(String fingerprint,long expiresAt) {}
    private final Map<String,ApprovalGrant> approved;
    private final List<OperationVerifier> verifiers;
    private volatile String question, finalAnswer;
    public TaskExecution(CoordinationStore store,TaskRecord task,GoalRecord goal,RunBudget budget,PolicyEngine policy,
                         Clock clock,Map<String,ApprovalGrant> approved){
        this(store,task,goal,budget,policy,clock,approved,List.of());
    }
    public TaskExecution(CoordinationStore store,TaskRecord task,GoalRecord goal,RunBudget budget,PolicyEngine policy,
                         Clock clock,Map<String,ApprovalGrant> approved,List<OperationVerifier> verifiers){
        this.store=store;taskId=task.id();this.goal=goal;this.budget=budget;this.policy=policy;this.clock=clock;this.approved=Map.copyOf(approved);
        this.verifiers=List.copyOf(verifiers);
    }
    public RunBudget budget(){return budget;}
    public String question(){return question;}
    public String finalAnswer(){return finalAnswer;}
    public synchronized void requestInput(String text){businessActive();if(text==null||text.isBlank()||text.length()>2000)throw new IllegalArgumentException("补问无效");question=text;}
    public synchronized void finish(String text){businessActive();if(text==null||text.isBlank()||text.length()>12000)throw new IllegalArgumentException("结果无效");finalAnswer=text;}
    public TaskRecord businessActive(){
        var task=active();
        if(question!=null||finalAnswer!=null)throw new IllegalStateException("本轮已结束，不再执行工具");
        return task;
    }
    public TaskRecord active(){
        budget.active();var task=store.task(taskId).orElseThrow();
        if(task.status()!=TaskStatus.RUNNING || task.goalRevision()!=goal.revision()
                || store.goal(task.sessionId()).orElseThrow().revision()!=goal.revision())throw new IllegalStateException("任务已失效或取消");
        TaskFacts.bind(store,task,clock.millis());
        return task;
    }
    public boolean humanApproved(ToolUseBlock call,String contractHash){
        var grant=approved.get(call.getId());
        return grant!=null&&clock.millis()<grant.expiresAt()&&(JsonFingerprint.hash(call.getInput())+"|"+contractHash).equals(grant.fingerprint());
    }
    public void validateApproval(ToolUseBlock call,String contractHash){
        if(approved.containsKey(call.getId())&&!humanApproved(call,contractHash))throw new IllegalStateException("调用参数或工具契约已改变，本次批准失效");
    }
    public synchronized String beforeEffect(ToolUseBlock call,String contractHash){
        return beforeEffect(call,contractHash,"");
    }
    public synchronized String beforeEffect(ToolUseBlock call,String contractHash,String scopeKey){
        var task=businessActive();
        if(task.spec().scope()!=TaskScope.REQUEST_APPROVAL)throw new IllegalStateException("当前任务仅允许查询与准备");
        if(task.effectStarted())throw new IllegalStateException("已有提交记录，需核查结果，禁止重复提交");
        if(policy.isCircuitOpen()&&!humanApproved(call,contractHash))throw new IllegalStateException("急停开启，操作需要本次人工确认");
        validateApproval(call,contractHash);
        if(!org.celanwang.mio.config.ToolPolicies.isJevReviewable(call.getName(),call.getInput())&&!humanApproved(call,contractHash))
            throw new IllegalStateException("当前写操作缺少本次人工批准");
        String id=task.id()+":"+call.getId();
        var operation=new OperationRecord(id,call.getId(),call.getName(),JsonFingerprint.hash(call.getInput()),clock.millis(),"SUBMITTING","",OperationOutcome.UNKNOWN,"",List.of(),UUID.randomUUID().toString(),scopeKey);
        store.startOperation(task,operation,clock.millis());return id;
    }
    public OperationRecord operation(String id){return store.task(taskId).orElseThrow().operations().stream().filter(o->o.id().equals(id)).findFirst().orElseThrow();}
    public synchronized void effectReturned(String id,String output){effectReturned(id,output,"SUCCESS");}
    public synchronized void effectReturned(String id,String output,String frameworkState){
        var task=store.task(taskId).orElseThrow();
        var old=task.operations().stream().filter(o->o.id().equals(id)).findFirst().orElseThrow();
        String raw=output==null?"":output;
        var verification=new OperationVerifier.Verification(OperationOutcome.UNKNOWN,"","unverified-tool-receipt");
        // SDK ToolResultBlock.text uses RUNNING for unspecified state. Only provider evidence can resolve it.
        if(("SUCCESS".equals(frameworkState)||"RUNNING".equals(frameworkState))&&raw.length()<=128000){
            try{
                var adapter=verifiers.stream().filter(v->v.supports(old.tool())).findFirst();
                if(adapter.isPresent()){
                    var checked=adapter.get().verify(raw);
                    if(checked!=null&&checked.outcome()!=null&&checked.source()!=null&&!checked.source().isBlank()
                            &&(checked.outcome()==OperationOutcome.UNKNOWN||checked.businessId()!=null&&!checked.businessId().isBlank()))verification=checked;
                }
            }catch(RuntimeException failed){
                verification=new OperationVerifier.Verification(OperationOutcome.UNKNOWN,"","verifier-error");
            }
        }
        String fingerprint=JsonFingerprint.hash(Map.of("operation",id,"state",frameworkState,"raw",raw,
                "verifier",verification.source(),"outcome",verification.outcome().name(),"businessId",verification.businessId()==null?"":verification.businessId()));
        var receipt=new OperationReceipt(fingerprint,verification.source(),frameworkState,raw.substring(0,Math.min(raw.length(),16000)),
                verification.outcome(),verification.businessId(),clock.millis());
        store.recordReceipt(taskId,id,receipt,clock.millis());
    }
}
