package org.celanwang.mio.orchestration;

/** Only actual user input may extend trustedIntent. Planner instructions are separate TaskSpec data. */
public record GoalRecord(String id, String sessionId, long revision, String trustedIntent, String latestUserInput,
                         long createdAt, long updatedAt,String inputTaskId,java.util.List<UserTurn> turns,State state,long controlRevision) {
    public enum State { ACTIVE,PAUSED,CLOSED }
    public static final class Stopped extends IllegalStateException {public Stopped(){super("目标已暂停、关闭或控制版本已改变，请先核对目标状态");}}
    public GoalRecord(String id,String sessionId,long revision,String trustedIntent,String latestUserInput,long createdAt,long updatedAt,String inputTaskId,java.util.List<UserTurn> turns){
        this(id,sessionId,revision,trustedIntent,latestUserInput,createdAt,updatedAt,inputTaskId,turns,State.ACTIVE,0);
    }
    public record UserTurn(long revision,String text) {}
    public GoalRecord {
        state=state==null?State.ACTIVE:state;
        if(controlRevision<0)throw new IllegalArgumentException("目标控制版本无效");
        // Legacy snapshots cannot be safely split on a delimiter that may occur in user text.
        turns=turns==null?java.util.List.of(new UserTurn(revision,trustedIntent)):java.util.List.copyOf(turns);
        long previous=0;
        for(var turn:turns){
            if(turn.revision()<=previous||turn.revision()>revision||turn.text()==null)throw new IllegalArgumentException("用户表达时间线损坏");
            previous=turn.revision();
        }
        if(previous!=revision)throw new IllegalArgumentException("用户表达时间线缺少当前版本");
        String reconstructed=turns.get(0).text();
        for(int i=1;i<turns.size();i++)reconstructed+="\n用户后续表达："+turns.get(i).text();
        if(!java.util.Objects.equals(trustedIntent,reconstructed))throw new IllegalArgumentException("用户表达与时间线不一致");
    }
    public GoalRecord(String id,String sessionId,long revision,String trustedIntent,String latestUserInput,long createdAt,long updatedAt,String inputTaskId){
        this(id,sessionId,revision,trustedIntent,latestUserInput,createdAt,updatedAt,inputTaskId,null);
    }
    public GoalRecord(String id,String sessionId,long revision,String trustedIntent,String latestUserInput,long createdAt,long updatedAt){
        this(id,sessionId,revision,trustedIntent,latestUserInput,createdAt,updatedAt,null);
    }
    public void requireActive(){if(state!=State.ACTIVE)throw new Stopped();}
    public GoalRecord transition(State target,long now){
        if(target==null)throw new IllegalArgumentException("目标状态缺失");
        if(target==state)return this;
        if(state==State.CLOSED)throw new IllegalStateException("已关闭的目标不能重新打开，请开启新对话");
        return new GoalRecord(id,sessionId,revision,trustedIntent,latestUserInput,createdAt,now,inputTaskId,turns,target,Math.addExact(controlRevision,1));
    }
    public GoalRecord appendUser(String input, long now) {
        return appendUser(input,now,null);
    }
    public GoalRecord appendUser(String input,long now,String inputTaskId){
        requireActive();
        if (input == null || input.isBlank() || input.length() > 10000) throw new IllegalArgumentException("用户消息无效");
        String combined = trustedIntent + "\n用户后续表达：" + input;
        if (combined.length() > 24000) throw new IllegalStateException("目标上下文已满，请开启新的对话");
        var history=new java.util.ArrayList<>(turns);history.add(new UserTurn(revision+1,input));
        return new GoalRecord(id,sessionId,revision+1,combined,input,createdAt,now,inputTaskId,history,state,controlRevision);
    }
}
