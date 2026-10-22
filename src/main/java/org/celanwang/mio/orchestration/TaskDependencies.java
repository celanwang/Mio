package org.celanwang.mio.orchestration;

import java.util.*;
import static org.celanwang.mio.orchestration.TaskInvalidation.Code.*;

/** Deterministic dependency validation, including completed intermediate tasks. No model inference. */
public final class TaskDependencies {
    public static Optional<TaskInvalidation> invalidation(CoordinationStore store,TaskRecord task,long now){return issues(store,task,now).stream().findFirst();}
    /** Keep the first persisted cause, but also expose additional roots that fail later. */
    public static List<TaskInvalidation> issues(CoordinationStore store,TaskRecord task,long now){
        return inspect(store,task,now,new HashSet<>(),new HashMap<>());
    }
    private static List<TaskInvalidation> inspect(CoordinationStore store,TaskRecord task,long now,Set<String> visiting,Map<String,List<TaskInvalidation>> checked){
        if(checked.containsKey(task.id()))return checked.get(task.id());
        if(!visiting.add(task.id()))return List.of(cause(DEPENDENCY_CYCLE,task.id(),"","",now));
        var result=new ArrayList<TaskInvalidation>();
        if(task.invalidation()!=null)result.add(task.invalidation());
        try{
            for(String id:task.spec().dependencies()){
                var found=store.task(id);
                if(found.isEmpty()){result.add(cause(DEPENDENCY_MISSING,id,"","",now));continue;}
                var source=found.get();
                if(!source.sessionId().equals(task.sessionId())||!source.goalId().equals(task.goalId())||source.goalRevision()!=task.goalRevision()){
                    result.add(cause(DEPENDENCY_SCOPE,id,"","",now));continue;
                }
                var upstream=inspect(store,source,now,visiting,checked);
                if(!upstream.isEmpty()){
                    for(var issue:upstream)result.add(new TaskInvalidation(DEPENDENCY_INVALID,id,issue.rootTaskId(),issue.factId(),issue.alias(),now));
                    continue;
                }
                if(source.status()!=TaskStatus.COMPLETED){
                    if(source.status().terminal())result.add(cause(DEPENDENCY_FAILED,id,"","",now));
                    else if(task.status()!=TaskStatus.WAITING_DEPENDENCY)result.add(cause(DEPENDENCY_NOT_COMPLETE,id,"","",now));
                    continue;
                }
                for(var binding:task.spec().inputs())if(binding.taskId().equals(id)){
                    var fact=TaskFacts.of(source).stream().filter(f->f.id().equals(binding.factId())).findFirst();
                    var code=fact.isEmpty()?FACT_MISSING:!fact.get().version().equals(binding.version())?FACT_CHANGED:
                            now<fact.get().observedAt()?FACT_TIME_INVALID:now>=fact.get().expiresAt()?FACT_EXPIRED:null;
                    if(code!=null)result.add(cause(code,id,binding.factId(),binding.alias(),now));
                }
            }
            var unique=new LinkedHashMap<String,TaskInvalidation>();
            for(var issue:result)unique.putIfAbsent(issue.code()+"\0"+issue.sourceTaskId()+"\0"+issue.rootTaskId()+"\0"+issue.factId()+"\0"+issue.alias(),issue);
            var issues=List.copyOf(unique.values());checked.put(task.id(),issues);return issues;
        }finally{visiting.remove(task.id());}
    }
    private static TaskInvalidation cause(TaskInvalidation.Code code,String source,String fact,String alias,long now){return new TaskInvalidation(code,source,source,fact,alias,now);}
    public static void requireReady(CoordinationStore store,TaskRecord task,long now){
        var invalid=invalidation(store,task,now);
        if(invalid.isPresent())throw new TaskFacts.InputUnavailable(invalid.get());
        for(String id:task.spec().dependencies())if(store.task(id).orElseThrow().status()!=TaskStatus.COMPLETED)
            throw new TaskFacts.InputUnavailable(cause(DEPENDENCY_NOT_COMPLETE,id,"","",now));
    }
    /** Lock all ancestors before an irreversible submission, including edges without fact bindings. */
    public static List<String> ancestors(CoordinationStore store,TaskRecord task){
        var result=new TreeSet<String>();var pending=new ArrayDeque<>(task.spec().dependencies());
        while(!pending.isEmpty()){
            String id=pending.removeFirst();
            if(id.equals(task.id()))throw new TaskFacts.InputUnavailable("依赖存在循环");
            if(result.add(id))store.task(id).ifPresent(t->{if(t.sessionId().equals(task.sessionId())&&t.goalId().equals(task.goalId()))pending.addAll(t.spec().dependencies());});
        }
        return List.copyOf(result);
    }
    private TaskDependencies(){}
}
