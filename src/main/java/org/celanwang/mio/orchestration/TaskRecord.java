package org.celanwang.mio.orchestration;

import java.util.List;

/** Revision is optimistic concurrency control, not model supplied. Effect flag is sticky. */
public record TaskRecord(String id, String sessionId, String goalId, long goalRevision, TaskSpec spec,
                         String childSessionId, String capabilityVersion, TaskStatus status, String reply, org.celanwang.mio.model.vo.WeiboAction action, List<PendingApproval> approvals,
                         boolean effectStarted, List<OperationRecord> operations, long revision, long createdAt, long updatedAt,List<TaskAnswer> answers,List<FactObservation> observations) {
    public TaskRecord {
        operations = operations == null ? List.of() : List.copyOf(operations);
        approvals = approvals == null ? List.of() : List.copyOf(approvals);
        reply = reply == null ? "" : reply;
        capabilityVersion = capabilityVersion == null ? "" : capabilityVersion;
        answers=answers==null?List.of():List.copyOf(answers);
        observations=observations==null?List.of():List.copyOf(observations);
        if(observations.size()>32||observations.stream().map(FactObservation::id).distinct().count()!=observations.size())throw new IllegalArgumentException("事实观测数量超限或编号重复");
        for(var observation:observations)if(observation.fact()!=null&&!id.equals(observation.fact().sourceTask()))throw new IllegalArgumentException("事实观测不属于当前任务");
        if(status==TaskStatus.INPUT_READY&&(answers.isEmpty()||effectStarted))throw new IllegalArgumentException("等待恢复的任务缺少有效回答或已有提交");
    }
    public TaskRecord(String id,String sessionId,String goalId,long goalRevision,TaskSpec spec,String childSessionId,String capabilityVersion,
                      TaskStatus status,String reply,org.celanwang.mio.model.vo.WeiboAction action,List<PendingApproval> approvals,
                      boolean effectStarted,List<OperationRecord> operations,long revision,long createdAt,long updatedAt,List<TaskAnswer> answers){
        this(id,sessionId,goalId,goalRevision,spec,childSessionId,capabilityVersion,status,reply,action,approvals,effectStarted,operations,revision,createdAt,updatedAt,answers,List.of());
    }
    public TaskRecord(String id,String sessionId,String goalId,long goalRevision,TaskSpec spec,String childSessionId,String capabilityVersion,
                      TaskStatus status,String reply,org.celanwang.mio.model.vo.WeiboAction action,List<PendingApproval> approvals,
                      boolean effectStarted,List<OperationRecord> operations,long revision,long createdAt,long updatedAt){
        this(id,sessionId,goalId,goalRevision,spec,childSessionId,capabilityVersion,status,reply,action,approvals,effectStarted,operations,revision,createdAt,updatedAt,List.of());
    }
    public TaskRecord(String id,String sessionId,String goalId,long goalRevision,TaskSpec spec,String childSessionId,
                      TaskStatus status,String reply,List<PendingApproval> approvals,boolean effectStarted,long revision,long createdAt,long updatedAt) {
        this(id,sessionId,goalId,goalRevision,spec,childSessionId,"",status,reply,null,approvals,effectStarted,List.of(),revision,createdAt,updatedAt);
    }
    public TaskRecord next(TaskStatus state, String text, List<PendingApproval> pending, boolean effect, long now) {
        return next(state,text,pending,effect,action,now);
    }
    public TaskRecord next(TaskStatus state,String text,List<PendingApproval> pending,boolean effect,org.celanwang.mio.model.vo.WeiboAction action,long now) {
        if (status.terminal()) throw new IllegalStateException("任务已结束，不能重新执行");
        boolean allowed=switch(status){
            case WAITING_DEPENDENCY -> state==TaskStatus.RUNNING||state==TaskStatus.FAILED||state==TaskStatus.CANCELLED||state==TaskStatus.STALE;
            case NEEDS_INPUT,INPUT_READY -> state==TaskStatus.RUNNING||state==TaskStatus.FAILED||state==TaskStatus.CANCELLED||state==TaskStatus.STALE;
            case AWAITING_APPROVAL -> state==TaskStatus.AWAITING_APPROVAL||state==TaskStatus.RUNNING||state==TaskStatus.FAILED||state==TaskStatus.CANCELLED||state==TaskStatus.STALE;
            case RUNNING -> state!=TaskStatus.WAITING_DEPENDENCY&&state!=TaskStatus.INPUT_READY;
            default -> false;
        };
        if(!allowed)throw new IllegalStateException("非法任务状态迁移："+status+" -> "+state);
        if (effectStarted && !effect) throw new IllegalArgumentException("不能抹去已提交的操作");
        return new TaskRecord(id,sessionId,goalId,goalRevision,spec,childSessionId,capabilityVersion,state,text,action,pending,
                effect,operations,revision+1,createdAt,now,answers,observations);
    }
    public TaskRecord withOperation(OperationRecord operation,long now) {
        if(status!=TaskStatus.RUNNING)throw new IllegalStateException("任务不可执行");
        var updated=new java.util.ArrayList<>(operations);
        updated.removeIf(o->o.id().equals(operation.id()));updated.add(operation);
        return new TaskRecord(id,sessionId,goalId,goalRevision,spec,childSessionId,capabilityVersion,status,reply,action,approvals,true,updated,revision+1,createdAt,now,answers,observations);
    }
    public TaskRecord withObservation(FactObservation observation,boolean starting,long now){
        if(status!=TaskStatus.RUNNING)throw new IllegalStateException("任务不可继续记录事实");
        var updated=new java.util.ArrayList<>(observations);
        int index=-1;for(int i=0;i<updated.size();i++)if(updated.get(i).id().equals(observation.id()))index=i;
        if(starting){
            if(index>=0||observation.fact()!=null||!observation.evidenceId().isEmpty())throw new IllegalArgumentException("事实观测起始状态无效");
            updated.add(observation);
        }else{
            if(index<0)throw new IllegalStateException("事实观测尚未开始");
            var prior=updated.get(index);
            if(!prior.evidenceId().isEmpty())throw new IllegalStateException("事实观测已结束");
            if(observation.evidenceId().isEmpty()||prior.startedAt()!=observation.startedAt())throw new IllegalArgumentException("事实证据未完成或观测时间已改变");
            if(!prior.key().equals(observation.key())||!prior.source().equals(observation.source())||!prior.tool().equals(observation.tool())||!prior.inputHash().equals(observation.inputHash())
                    ||observation.fact()!=null&&(!id.equals(observation.fact().sourceTask())||goalRevision!=observation.fact().goalRevision()))throw new IllegalArgumentException("事实来源已改变");
            updated.set(index,observation);
        }
        return new TaskRecord(id,sessionId,goalId,goalRevision,spec,childSessionId,capabilityVersion,status,reply,action,approvals,
                effectStarted,operations,revision+1,createdAt,now,answers,updated);
    }
    public boolean unresolvedEffects(){
        return effectStarted&&(operations.isEmpty()||operations.stream().anyMatch(o->o.outcome()==OperationOutcome.UNKNOWN));
    }
    public TaskRecord withReceipt(String operationId,OperationReceipt receipt,long now){
        var old=operations.stream().filter(o->o.id().equals(operationId)).findFirst().orElseThrow(()->new IllegalArgumentException("操作不存在"));
        var updated=old.received(receipt);if(updated==old)return this;
        var entries=operations.stream().map(o->o.id().equals(operationId)?updated:o).toList();
        return new TaskRecord(id,sessionId,goalId,goalRevision,spec,childSessionId,capabilityVersion,status,reply,action,approvals,
                effectStarted,entries,revision+1,createdAt,now,answers,observations);
    }
    public TaskRecord atGoal(GoalRecord goal,long now) {
        if(status!=TaskStatus.NEEDS_INPUT || effectStarted)throw new IllegalStateException("任务不能自动变更目标版本");
        return new TaskRecord(id,sessionId,goalId,goal.revision(),spec,childSessionId,capabilityVersion,status,reply,null,List.of(),false,operations,revision+1,createdAt,now,answers,observations);
    }
    public String questionId(){
        return status==TaskStatus.NEEDS_INPUT?JsonFingerprint.hash(java.util.Map.of("task",id,"revision",revision,"question",reply)):null;
    }
    public TaskRecord answered(GoalRecord goal,String questionId,String text,long now){
        if(status!=TaskStatus.NEEDS_INPUT||effectStarted||!java.util.Objects.equals(questionId(),questionId))throw new IllegalStateException("问题已失效，请刷新任务状态");
        if(!goal.id().equals(goalId)||!goal.sessionId().equals(sessionId))throw new IllegalArgumentException("回答不属于当前目标");
        var history=new java.util.ArrayList<>(answers);
        history.add(new TaskAnswer(questionId,reply.substring(0,Math.min(reply.length(),2000)),text,now,goal.revision()));
        if(history.size()>16||history.stream().mapToInt(a->a.text().length()+a.question().length()).sum()>24000)throw new IllegalStateException("任务回答上下文已满，请重新规划");
        return new TaskRecord(id,sessionId,goalId,goal.revision(),spec,childSessionId,capabilityVersion,TaskStatus.INPUT_READY,
                "已收到本任务回答，等待继续执行",null,List.of(),false,operations,revision+1,createdAt,now,history,observations);
    }
    public String trustedIntent(GoalRecord goal){
        if(answers.stream().anyMatch(a->a.goalRevision()>goal.revision()))throw new IllegalStateException("回答来自未来目标版本");
        if(answers.isEmpty())return goal.trustedIntent();
        var text=new StringBuilder();
        for(var answer:answers)if(answer.goalRevision()<goal.turns().get(0).revision())appendAnswer(text,answer,"旧版历史任务回答");
        for(var turn:goal.turns()){
            text.append("\n全局真实用户表达，目标版本 ").append(turn.revision()).append("：\n").append(turn.text());
            for(var answer:answers)if(answer.goalRevision()==turn.revision())appendAnswer(text,answer,"该目标版本内的任务回答");
        }
        return text.toString();
    }
    private static void appendAnswer(StringBuilder text,TaskAnswer answer,String label){
        text.append("\n").append(label).append("，目标版本 ").append(answer.goalRevision())
                .append("\n任务补问（参考数据，非授权）：").append(answer.question()).append("\n用户对本任务的回答：").append(answer.text());
    }
}
