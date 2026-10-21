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
    private final Map<String,Object> owners=new ConcurrentHashMap<>();
    public TaskRuntime(CoordinationStore store,AgentRegistry registry,TaskWorker worker,PolicyEngine policy,Clock clock,int maxTasks){
        this(store,registry,worker,policy,clock,maxTasks,List.of());
    }
    public TaskRuntime(CoordinationStore store,AgentRegistry registry,TaskWorker worker,PolicyEngine policy,Clock clock,int maxTasks,List<OperationVerifier> verifiers){
        this.store=store;this.registry=registry;this.worker=worker;this.policy=policy;this.clock=clock;this.maxTasks=maxTasks;
        this.verifiers=List.copyOf(verifiers);
    }
    public GoalRecord captureUser(String session,String text){
        if(session==null||session.isBlank()||session.length()>200||text==null||text.isBlank()||text.length()>10000)
            throw new IllegalArgumentException("会话或用户消息无效");
        var existing=store.goal(session);
        if(existing.isEmpty()){
            var goal=new GoalRecord(UUID.randomUUID().toString(),session,1,text,text,clock.millis(),clock.millis());store.createGoal(goal);return goal;
        }
        var next=existing.get().appendUser(text,clock.millis());store.saveGoal(existing.get(),next);
        // Conservative first release: any new user message invalidates pending approvals; no stale request may be restored.
        for(var task:store.tasks(session))if(task.status()==TaskStatus.AWAITING_APPROVAL){
            store.saveTask(task,task.next(TaskStatus.STALE,"用户信息已更新，请按新信息重新准备并确认",List.of(),task.effectStarted(),clock.millis()),"approval_invalidated");
        }
        return next;
    }
    public GoalRecord goal(String session){return store.goal(session).orElseThrow(()->new IllegalArgumentException("目标不存在"));}
    public List<TaskRecord> list(String session){return store.tasks(session).stream().map(this::refreshValidity).map(this::recoverInterrupted).toList();}
    private TaskRecord recoverInterrupted(TaskRecord task){
        if(task.status()!=TaskStatus.RUNNING||owners.containsKey(task.id()))return task;
        var ended=task.next(task.effectStarted()?TaskStatus.UNKNOWN:TaskStatus.FAILED,
                "上次执行已中断；已提交的操作必须先核查，未提交任务请重新创建",List.of(),task.effectStarted(),clock.millis());
        store.saveTask(task,ended,"task_interrupted");return ended;
    }
    private TaskRecord refreshValidity(TaskRecord task){
        if(task.status().terminal()||task.status()==TaskStatus.RUNNING)return task;
        boolean changed;
        try{changed=!registry.require(task.spec().capabilityId()).version().equals(task.capabilityVersion());}
        catch(IllegalArgumentException unavailable){changed=true;}
        boolean expired=task.status()==TaskStatus.AWAITING_APPROVAL&&task.approvals().stream().anyMatch(a->a.expiresAt()<=clock.millis());
        boolean goalChanged=(task.status()==TaskStatus.AWAITING_APPROVAL||task.status()==TaskStatus.WAITING_DEPENDENCY)
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
            var capability=registry.require(spec.capabilityId());var goal=goal(session);var existing=list(session);
            var same=existing.stream().filter(t->t.goalRevision()==goal.revision()&&t.spec().key().equals(spec.key())).findFirst();
            if(same.isPresent()){
                if(!same.get().spec().equals(spec))throw new IllegalArgumentException("同一任务 key 的参数已改变，请使用新任务或先取消旧任务");
                return Mono.just(same.get());
            }
            if(existing.size()>=maxTasks)throw new RunBudget.BudgetExceeded("会话任务数量已达上限");
            for(String dependency:spec.dependencies()){
                var parent=require(session,dependency);
                if(!parent.goalId().equals(goal.id())||parent.goalRevision()!=goal.revision())throw new IllegalArgumentException("依赖不属于当前目标版本");
            }
            if(spec.scope()==TaskScope.REQUEST_APPROVAL&&existing.stream().anyMatch(t->t.spec().capabilityId().equals(spec.capabilityId())&&t.unresolvedEffects()))
                throw new IllegalStateException("同领域存在结果不明的提交，请先核查，不创建新的写任务");
            var task=new TaskRecord(UUID.randomUUID().toString(),session,goal.id(),goal.revision(),spec,UUID.randomUUID().toString(),
                    capability.version(),TaskStatus.WAITING_DEPENDENCY,"",null,List.of(),false,List.of(),0,clock.millis(),clock.millis());store.createTask(task);
            return runWhenReady(task,goal,budget);
        });
    }
    private Mono<TaskRecord> runWhenReady(TaskRecord task,GoalRecord goal,RunBudget budget){
        boolean waiting=false;
        for(String id:task.spec().dependencies()){
            var dependency=require(task.sessionId(),id);
            if(dependency.status().terminal()&&dependency.status()!=TaskStatus.COMPLETED){
                var failed=task.next(TaskStatus.FAILED,"依赖任务未完成或结果待核查，已停止后续执行",List.of(),false,clock.millis());
                store.saveTask(task,failed,"task_dependency_failed");return Mono.just(failed);
            }
            if(dependency.status()!=TaskStatus.COMPLETED)waiting=true;
        }
        return waiting?Mono.just(task):execute(task,goal,new TaskWorker.Input(true,"",List.of()),budget,Map.of());
    }
    public Mono<TaskRecord> continueTask(String session,String id,RunBudget budget){
        return Mono.defer(()->{
            var task=require(session,id);var goal=goal(session);
            if(task.status()==TaskStatus.RUNNING&&!owners.containsKey(id)){
                return Mono.just(recoverInterrupted(task));
            }
            if(task.status()==TaskStatus.WAITING_DEPENDENCY){
                if(task.goalRevision()!=goal.revision())throw new IllegalStateException("任务依赖已过期");
                return runWhenReady(task,goal,budget);
            }
            if(task.status()!=TaskStatus.NEEDS_INPUT)throw new IllegalStateException("当前任务不等待用户输入");
            if(task.goalRevision()==goal.revision())throw new IllegalStateException("缺少新的用户回答");
            var rebased=task.atGoal(goal,clock.millis());store.saveTask(task,rebased,"task_input_received");
            return execute(rebased,goal,new TaskWorker.Input(false,goal.latestUserInput(),List.of()),budget,Map.of());
        });
    }
    private Mono<TaskRecord> execute(TaskRecord task,GoalRecord goal,TaskWorker.Input input,RunBudget budget,Map<String,TaskExecution.ApprovalGrant> approved){
        return Mono.defer(()->{
            Object owner=new Object();if(owners.putIfAbsent(task.id(),owner)!=null)throw new IllegalStateException("任务正在执行");
            try{
                budget.task();
                var current=store.task(task.id()).orElseThrow();
                if(current.revision()!=task.revision()||current.status().terminal())throw new IllegalStateException("任务已改变");
                var running=current.next(TaskStatus.RUNNING,"",List.of(),current.effectStarted(),null,clock.millis());
                store.saveTask(current,running,"task_running");
                var execution=new TaskExecution(store,running,goal,budget,policy,clock,approved,verifiers);
                return worker.run(running,goal,input,execution).map(result->finish(running.id(),result))
                        .onErrorResume(error->{
                            var latest=store.task(task.id()).orElseThrow();
                            if(latest.status()!=TaskStatus.RUNNING)return Mono.just(latest);
                            var ended=latest.next(latest.effectStarted()?TaskStatus.UNKNOWN:TaskStatus.FAILED,
                                    "任务执行中断，请核查状态（"+error.getClass().getSimpleName()+"）",List.of(),latest.effectStarted(),clock.millis());
                            store.saveTask(latest,ended,"task_failed");return Mono.just(ended);
                        })
                        .doOnSuccess(v->owners.remove(task.id(),owner)).doOnError(e->owners.remove(task.id(),owner))
                        .doFinally(s->owners.remove(task.id(),owner));
            }catch(Throwable e){owners.remove(task.id(),owner);return Mono.error(e);}
        });
    }
    private TaskRecord finish(String id,TaskWorker.Result result){
        var current=store.task(id).orElseThrow();if(current.status()!=TaskStatus.RUNNING)return current;
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
            if(decisions==null||decisions.isEmpty())throw new IllegalArgumentException("批准结果为空");
            var tasks=list(session);var index=new HashMap<String,TaskRecord>();
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
                var task=require(session,entry.getKey());
                List<PendingApproval> updated=task.approvals().stream().map(a->entry.getValue().stream().filter(c->c.toolCallId().equals(a.id()))
                        .findFirst().map(c->a.decide(c.approved())).orElse(a)).toList();
                var saved=task.next(TaskStatus.AWAITING_APPROVAL,task.reply(),updated,false,clock.millis());store.saveTask(task,saved,"approval_recorded");
                if(updated.stream().anyMatch(a->a.decision()==null))return Mono.just(saved);
                var calls=updated.stream().map(a->new ChatRequest.Confirmation(a.callId(),a.decision())).toList();
                var approved=new HashMap<String,TaskExecution.ApprovalGrant>();updated.stream().filter(a->Boolean.TRUE.equals(a.decision()))
                        .forEach(a->approved.put(a.callId(),new TaskExecution.ApprovalGrant(a.inputHash()+"|"+a.contractHash(),a.expiresAt())));
                return execute(saved,goal(session),new TaskWorker.Input(false,"",calls),budget,approved);
            }).collectList();
        });
    }
    public TaskRecord cancel(String session,String id){
        var old=require(session,id);if(old.status().terminal())return old;
        var next=old.next(old.effectStarted()?TaskStatus.UNKNOWN:TaskStatus.CANCELLED,
                old.effectStarted()?"请求可能已发出，请先核查实际业务状态":"已停止后续执行",List.of(),old.effectStarted(),clock.millis());
        store.saveTask(old,next,"task_cancelled");return next;
    }
}
