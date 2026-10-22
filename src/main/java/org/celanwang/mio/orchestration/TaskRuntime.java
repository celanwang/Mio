package org.celanwang.mio.orchestration;

import org.celanwang.mio.model.dto.ChatRequest;
import org.celanwang.mio.model.vo.ToolConfirmation;
import org.celanwang.mio.security.PolicyEngine;
import reactor.core.publisher.Mono;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** No domain branches or planner-authored permissions. Every execution is fenced by stored task state. */
public final class TaskRuntime {
    private final CoordinationStore store;
    private final AgentRegistry registry;
    private final TaskWorker worker;
    private final PolicyEngine policy;
    private final Clock clock;
    private final int maxTasks;
    private final List<OperationVerifier> verifiers;
    private final List<OperationReconciler> reconcilers;
    private final List<FactExtractor> factExtractors;
    private final ExecutionLeaseStore leases;
    private final Map<String,Object> owners=new ConcurrentHashMap<>();
    public TaskRuntime(CoordinationStore store,AgentRegistry registry,TaskWorker worker,PolicyEngine policy,Clock clock,int maxTasks){
        this(store,registry,worker,policy,clock,maxTasks,List.of());
    }
    public TaskRuntime(CoordinationStore store,AgentRegistry registry,TaskWorker worker,PolicyEngine policy,Clock clock,int maxTasks,List<OperationVerifier> verifiers){
        this(store,registry,worker,policy,clock,maxTasks,verifiers,List.of());
    }
    public TaskRuntime(CoordinationStore store,AgentRegistry registry,TaskWorker worker,PolicyEngine policy,Clock clock,int maxTasks,List<OperationVerifier> verifiers,List<OperationReconciler> reconcilers){
        this(store,registry,worker,policy,clock,maxTasks,verifiers,reconcilers,List.of());
    }
    public TaskRuntime(CoordinationStore store,AgentRegistry registry,TaskWorker worker,PolicyEngine policy,Clock clock,int maxTasks,List<OperationVerifier> verifiers,List<OperationReconciler> reconcilers,List<FactExtractor> factExtractors){
        this(store,registry,worker,policy,clock,maxTasks,verifiers,reconcilers,factExtractors,null);
    }
    public TaskRuntime(CoordinationStore store,AgentRegistry registry,TaskWorker worker,PolicyEngine policy,Clock clock,int maxTasks,List<OperationVerifier> verifiers,List<OperationReconciler> reconcilers,List<FactExtractor> factExtractors,ExecutionLeaseStore leases){
        this.leases=leases;
        this.factExtractors=List.copyOf(factExtractors);
        this.store=store;this.registry=registry;this.worker=worker;this.policy=policy;this.clock=clock;this.maxTasks=maxTasks;
        this.verifiers=List.copyOf(verifiers);
        this.reconcilers=List.copyOf(reconcilers);
    }
    private void requireLease(String session,RunBudget budget){
        if(leases!=null&&budget.lease()==null)throw new IllegalStateException("领域执行缺少持久会话租约");
        if(budget.lease()!=null&&!budget.lease().resource().equals(ExecutionLeaseStore.resource(session)))throw new IllegalArgumentException("执行租约不属于当前会话");
        budget.active();
    }
    public GoalRecord captureUser(String session,String text){
        if(session==null||session.isBlank()||session.length()>200||text==null||text.isBlank()||text.length()>10000)
            throw new IllegalArgumentException("会话或用户消息无效");
        var existing=store.goal(session);
        if(existing.isEmpty()){
            var goal=new GoalRecord(UUID.randomUUID().toString(),session,1,text,text,clock.millis(),clock.millis());store.createGoal(goal);return goal;
        }
        var questions=list(session).stream().filter(t->t.status()==TaskStatus.NEEDS_INPUT).toList();
        var next=existing.get().appendUser(text,clock.millis(),questions.size()==1?questions.get(0).id():null);store.saveGoal(existing.get(),next);
        // Conservative first release: any new user message invalidates pending approvals; no stale request may be restored.
        for(var task:store.tasks(session))if(task.status()==TaskStatus.AWAITING_APPROVAL){
            store.saveTask(task,task.next(TaskStatus.STALE,"用户信息已更新，请按新信息重新准备并确认",List.of(),task.effectStarted(),clock.millis()),"approval_invalidated");
        }
        return next;
    }
    public PlanReview reviewPlan(String session){return PlanReview.of(goal(session),list(session),clock.millis(),store);}
    public GoalRecord goal(String session){return store.goal(session).orElseThrow(()->new IllegalArgumentException("目标不存在"));}
    public Optional<GoalRecord> findGoal(String session){return store.goal(session);}
    public List<TaskRecord> list(String session){return store.tasks(session).stream().map(this::refreshValidity).map(this::recoverInterrupted).toList();}
    private TaskRecord recoverInterrupted(TaskRecord task){
        if(task.status()!=TaskStatus.RUNNING)return task;
        if(leases==null?owners.containsKey(task.id()):leases.valid(task.executionLease()))return task;
        var ended=task.next(task.effectStarted()?TaskStatus.UNKNOWN:TaskStatus.FAILED,
                "上次执行已中断；已提交的操作必须先核查，未提交任务请重新创建",List.of(),task.effectStarted(),clock.millis());
        store.saveTask(task,ended,"task_interrupted");return ended;
    }
    private TaskRecord refreshValidity(TaskRecord task){
        if(task.invalidation()==null&&!task.spec().dependencies().isEmpty()&&(!task.status().terminal()||task.status()==TaskStatus.COMPLETED))task=store.invalidateDependencies(task.id(),clock.millis());
        if(task.status().terminal()||task.status()==TaskStatus.RUNNING)return task;
        boolean changed;
        try{changed=!registry.require(task.spec().capabilityId()).executionVersion().equals(task.capabilityVersion());}
        catch(IllegalArgumentException unavailable){changed=true;}
        boolean expired=task.status()==TaskStatus.AWAITING_APPROVAL&&task.approvals().stream().anyMatch(a->a.expiresAt()<=clock.millis());
        boolean goalChanged=(task.status()==TaskStatus.AWAITING_APPROVAL||task.status()==TaskStatus.WAITING_DEPENDENCY||task.status()==TaskStatus.INPUT_READY)
                &&store.goal(task.sessionId()).orElseThrow().revision()!=task.goalRevision();
        if(!changed&&!expired&&!goalChanged)return task;
        var stale=task.next(TaskStatus.STALE,changed?"领域技能版本已变更，请重新准备任务":goalChanged?"用户目标已改变，请重新准备":"本次确认已过期，请重新准备",List.of(),task.effectStarted(),clock.millis());
        store.saveTask(task,stale,"task_invalidated");return stale;
    }
    public TaskRecord require(String session,String id){
        var task=store.task(id).orElseThrow(()->new IllegalArgumentException("任务不存在"));
        if(!task.sessionId().equals(session))throw new IllegalArgumentException("任务不属于当前会话");return refreshValidity(task);
    }
    public Mono<TaskRecord> submit(String session,TaskSpec spec,RunBudget budget){
        return Mono.defer(()->{
            requireLease(session,budget);
            var capability=registry.require(spec.capabilityId());capability.validateSpec(spec);var goal=goal(session);var existing=budget.fenced(()->list(session));
            var same=existing.stream().filter(t->t.goalRevision()==goal.revision()&&t.spec().key().equals(spec.key())).findFirst();
            if(same.isPresent()){
                if(!same.get().spec().equals(spec))throw new IllegalArgumentException("同一任务 key 的参数已改变，请使用新任务或先取消旧任务");
                return Mono.just(same.get());
            }
            registry.status(spec.capabilityId()).requireExecution();
            if(existing.size()>=maxTasks)throw new RunBudget.BudgetExceeded("会话任务数量已达上限");
            for(String dependency:spec.dependencies()){
                var parent=budget.fenced(()->require(session,dependency));
                if(!parent.goalId().equals(goal.id())||parent.goalRevision()!=goal.revision())throw new IllegalArgumentException("依赖不属于当前目标版本");
            }
            if(spec.scope()==TaskScope.REQUEST_APPROVAL&&existing.stream().anyMatch(t->t.spec().capabilityId().equals(spec.capabilityId())&&t.unresolvedEffects()))
                throw new IllegalStateException("同领域存在结果不明的提交，请先核查，不创建新的写任务");
            var task=new TaskRecord(UUID.randomUUID().toString(),session,goal.id(),goal.revision(),spec,UUID.randomUUID().toString(),
                    capability.executionVersion(),TaskStatus.WAITING_DEPENDENCY,"",null,List.of(),false,List.of(),0,clock.millis(),clock.millis());budget.fenced(()->{store.createTask(task);return null;});
            return runWhenReady(task,goal,budget);
        });
    }
    private Mono<TaskRecord> runWhenReady(TaskRecord task,GoalRecord goal,RunBudget budget){
        boolean waiting=false;
        for(String id:task.spec().dependencies()){
            var dependency=budget.fenced(()->require(task.sessionId(),id));
            if(dependency.invalidation()!=null)return Mono.just(budget.fenced(()->store.invalidateDependencies(task.id(),clock.millis())));
            if(dependency.status().terminal()&&dependency.status()!=TaskStatus.COMPLETED){
                return Mono.just(budget.fenced(()->store.invalidateDependencies(task.id(),clock.millis())));
            }
            if(dependency.status()!=TaskStatus.COMPLETED)waiting=true;
        }
        return waiting?Mono.just(task):execute(task,goal,new TaskWorker.Input(true,"",List.of()),budget,Map.of());
    }
    public Mono<TaskRecord> continueTask(String session,String id,RunBudget budget){
        return Mono.defer(()->{
            requireLease(session,budget);
            var task=budget.fenced(()->require(session,id));var goal=goal(session);
            if(task.status()==TaskStatus.RUNNING){
                return Mono.just(recoverInterrupted(task));
            }
            if(task.status()==TaskStatus.WAITING_DEPENDENCY){
                if(task.goalRevision()!=goal.revision())throw new IllegalStateException("任务依赖已过期");
                return runWhenReady(task,goal,budget);
            }
            if(task.status()==TaskStatus.INPUT_READY){
                return execute(task,goal,new TaskWorker.Input(false,task.answers().get(task.answers().size()-1).text(),List.of()),budget,Map.of());
            }
            if(task.status()!=TaskStatus.NEEDS_INPUT)throw new IllegalStateException("当前任务不等待用户输入");
            if(task.goalRevision()==goal.revision())throw new IllegalStateException("缺少新的用户回答");
            if(!task.id().equals(goal.inputTaskId()))throw new IllegalStateException("收到消息时存在多个任务或回答归属不明，请选择具体任务的问题");
            return answerTask(session,id,task.questionId(),goal.latestUserInput(),budget);
        });
    }
    public Mono<TaskRecord> answerTask(String session,String id,String questionId,String text,RunBudget budget){
        return Mono.defer(()->{
            requireLease(session,budget);
            var task=budget.fenced(()->require(session,id));var goal=goal(session);
            var ready=task.answered(goal,questionId,text,clock.millis());
            TaskFacts.bind(store,ready,clock.millis());
            budget.fenced(()->{store.saveTask(task,ready,"task_answer_received");return null;});
            return execute(ready,goal,new TaskWorker.Input(false,text,List.of()),budget,Map.of());
        });
    }
    private Mono<TaskRecord> execute(TaskRecord task,GoalRecord goal,TaskWorker.Input input,RunBudget budget,Map<String,TaskExecution.ApprovalGrant> approved){
        return execute(task,goal,input,budget,approved,false);
    }
    private Mono<TaskRecord> execute(TaskRecord task,GoalRecord goal,TaskWorker.Input input,RunBudget budget,Map<String,TaskExecution.ApprovalGrant> approved,boolean reserved){
        return Mono.defer(()->{
            if(leases!=null&&budget.lease()==null)throw new IllegalStateException("领域执行缺少持久会话租约");
            budget.active();
            Object owner=new Object();if(owners.putIfAbsent(task.id(),owner)!=null)throw new IllegalStateException("任务正在执行");
            try{
                var current=store.task(task.id()).orElseThrow();
                if(current.revision()!=task.revision()||current.status().terminal())throw new IllegalStateException("任务已改变");
                try{registry.status(task.spec().capabilityId()).requireExecution();}
                catch(CapabilityAvailability.Unavailable unavailable){
                    var failed=current.next(TaskStatus.FAILED,unavailable.getMessage(),List.of(),current.effectStarted(),clock.millis());
                    budget.fenced(()->{store.saveTask(current,failed,"capability_unavailable");return null;});owners.remove(task.id(),owner);return Mono.just(failed);
                }
                if(!reserved)budget.task();
                var running=current.start(budget.lease(),clock.millis());
                budget.fenced(()->{store.saveTask(current,running,"task_running");return null;});
                var execution=new TaskExecution(store,running,goal,budget,policy,clock,approved,verifiers,factExtractors,registry.require(task.spec().capabilityId()));
                return Mono.defer(()->{
                    var bound=new TaskWorker.Input(input.first(),input.userText(),input.decisions(),TaskFacts.bind(store,running,clock.millis()));
                    registry.require(running.spec().capabilityId()).validateInputs(running.spec(),bound.bindings());
                    return worker.run(running,goal,bound,execution);
                }).map(result->budget.fenced(()->finish(running.id(),result)))
                        .onErrorResume(error->Mono.fromSupplier(()->budget.fenced(()->{
                            var latest=store.task(task.id()).orElseThrow();
                            if(latest.status()!=TaskStatus.RUNNING)return latest;
                            if(error instanceof TaskFacts.InputUnavailable){
                                var invalid=store.invalidateDependencies(task.id(),clock.millis());
                                if(invalid.invalidation()!=null)return invalid;
                            }
                            var ended=latest.next(latest.effectStarted()?TaskStatus.UNKNOWN:TaskStatus.FAILED,
                                    error instanceof TaskFacts.InputUnavailable?"任务所需事实不可用："+error.getMessage():"任务执行中断，请核查状态（"+error.getClass().getSimpleName()+"）",List.of(),latest.effectStarted(),clock.millis());
                            store.saveTask(latest,ended,"task_failed");return ended;
                        })))
                        .doOnSuccess(v->owners.remove(task.id(),owner)).doOnError(e->owners.remove(task.id(),owner))
                        .doFinally(s->owners.remove(task.id(),owner));
            }catch(Throwable e){owners.remove(task.id(),owner);return Mono.error(e);}
        });
    }
    private TaskRecord finish(String id,TaskWorker.Result result){
        var current=store.task(id).orElseThrow();if(current.status()!=TaskStatus.RUNNING)return current;
        try{TaskFacts.bind(store,current,clock.millis());}
        catch(TaskFacts.InputUnavailable changed){
            var invalid=store.invalidateDependencies(id,clock.millis());
            if(invalid.invalidation()!=null)return invalid;
            var stale=current.next(current.effectStarted()?TaskStatus.UNKNOWN:TaskStatus.STALE,"事实输入已变更，需重新准备任务",List.of(),current.effectStarted(),clock.millis());
            store.saveTask(current,stale,"task_input_invalidated");return stale;
        }
        List<PendingApproval> pending=new ArrayList<>();
        for(ToolConfirmation call:result.pending())pending.add(new PendingApproval(UUID.randomUUID().toString(),call.toolCallId(),call.name(),
                call.description(),call.input(),JsonFingerprint.hash(call.input()),call.contractHash(),clock.millis()+300_000,null));
        boolean cannotResume=current.effectStarted()&&(!pending.isEmpty()||result.needsInput());
        if(cannotResume)pending.clear();
        TaskStatus status=current.unresolvedEffects()?TaskStatus.UNKNOWN:result.failed()||cannotResume?TaskStatus.FAILED:!pending.isEmpty()?TaskStatus.AWAITING_APPROVAL:
                result.needsInput()?TaskStatus.NEEDS_INPUT:TaskStatus.COMPLETED;
        String reply=current.unresolvedEffects()?"工具调用已返回，业务副作用仍需按回执核查。\n"+result.reply():result.reply();
        if(cannotResume)reply="本任务已经发出操作，后续步骤需另建任务，不能恢复或重复提交。\n"+reply;
        var next=current.next(status,reply,pending,current.effectStarted(),result.action(),clock.millis());
        store.saveTask(current,next,"task_updated");return next;
    }
    public Mono<List<TaskRecord>> approve(String session,List<ChatRequest.Confirmation> decisions,RunBudget budget){
        return Mono.defer(()->{
            requireLease(session,budget);
            if(decisions==null||decisions.isEmpty())throw new IllegalArgumentException("批准结果为空");
            var tasks=budget.fenced(()->list(session));var index=new HashMap<String,TaskRecord>();
            tasks.forEach(t->t.approvals().forEach(a->index.put(a.id(),t)));
            var seen=new HashSet<String>();var choices=new LinkedHashMap<String,List<ChatRequest.Confirmation>>();
            for(var choice:decisions){
                if(choice==null||choice.approved()==null||!seen.add(choice.toolCallId()))throw new IllegalArgumentException("批准结果缺失或重复");
                var task=index.get(choice.toolCallId());if(task==null)throw new IllegalArgumentException("批准已失效或不属于当前会话");
                var pending=task.approvals().stream().filter(a->a.id().equals(choice.toolCallId())).findFirst().orElseThrow();
                if(task.status()!=TaskStatus.AWAITING_APPROVAL||task.goalRevision()!=goal(session).revision()||pending.decision()!=null||clock.millis()>=pending.expiresAt())
                    throw new IllegalStateException("批准已失效，请重新准备");
                choices.computeIfAbsent(task.id(),k->new ArrayList<>()).add(choice);
            }
            return reactor.core.publisher.Flux.fromIterable(choices.entrySet()).concatMap(entry->{
                var task=budget.fenced(()->require(session,entry.getKey()));
                List<PendingApproval> updated=task.approvals().stream().map(a->entry.getValue().stream().filter(c->c.toolCallId().equals(a.id()))
                        .findFirst().map(c->a.decide(c.approved())).orElse(a)).toList();
                if(updated.stream().allMatch(a->Boolean.FALSE.equals(a.decision()))){
                    var rejected=task.next(TaskStatus.CANCELLED,"已拒绝本任务的操作，未继续执行",updated,false,clock.millis());
                    budget.fenced(()->{store.saveTask(task,rejected,"approval_rejected");return null;});return Mono.just(rejected);
                }
                boolean complete=updated.stream().noneMatch(a->a.decision()==null);
                if(complete){
                    registry.status(task.spec().capabilityId()).requireExecution();
                    registry.require(task.spec().capabilityId()).validateInputs(task.spec(),TaskFacts.bind(store,task,clock.millis()));
                    budget.task();
                }
                var saved=task.next(TaskStatus.AWAITING_APPROVAL,task.reply(),updated,false,clock.millis());budget.fenced(()->{store.saveTask(task,saved,"approval_recorded");return null;});
                if(!complete)return Mono.just(saved);
                var calls=updated.stream().map(a->new ChatRequest.Confirmation(a.callId(),a.decision())).toList();
                var approved=new HashMap<String,TaskExecution.ApprovalGrant>();updated.stream().filter(a->Boolean.TRUE.equals(a.decision()))
                        .forEach(a->approved.put(a.callId(),new TaskExecution.ApprovalGrant(a.inputHash()+"|"+a.contractHash(),a.expiresAt())));
                return execute(saved,goal(session),new TaskWorker.Input(false,"",calls),budget,approved,true);
            }).collectList();
        });
    }
    public TaskRecord cancel(String session,String id){
        var old=require(session,id);if(old.status().terminal())return old;
        var next=old.next(old.effectStarted()?TaskStatus.UNKNOWN:TaskStatus.CANCELLED,
                old.effectStarted()?"请求可能已发出，请先核查实际业务状态":"已停止后续执行",List.of(),old.effectStarted(),clock.millis());
        store.saveTask(old,next,"task_cancelled");return next;
    }
    public Mono<TaskRecord> reconcile(String session,String taskId,String operationId,RunBudget budget){
        return Mono.defer(()->{
            requireLease(session,budget);
            var task=budget.fenced(()->require(session,taskId));
            if(task.status()==TaskStatus.RUNNING||!task.effectStarted())throw new IllegalStateException("操作仍在执行或尚未提交，不能核查");
            var op=task.operations().stream().filter(o->o.id().equals(operationId)).findFirst().orElseThrow(()->new IllegalArgumentException("操作不存在"));
            var adapter=reconcilers.stream().filter(r->r.supports(op)).findFirst().orElseThrow(()->new IllegalStateException("该操作暂无可信关联核查能力，禁止重发"));
            budget.tool();
            return adapter.reconcile(task,op).map(receipt->{store.recordReceipt(taskId,operationId,receipt,clock.millis());return require(session,taskId);});
        });
    }
}
