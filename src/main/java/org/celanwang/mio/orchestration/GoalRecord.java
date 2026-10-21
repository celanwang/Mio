package org.celanwang.mio.orchestration;

/** Only actual user input may extend trustedIntent. Planner instructions are separate TaskSpec data. */
public record GoalRecord(String id, String sessionId, long revision, String trustedIntent, String latestUserInput,
                         long createdAt, long updatedAt,String inputTaskId) {
    public GoalRecord(String id,String sessionId,long revision,String trustedIntent,String latestUserInput,long createdAt,long updatedAt){
        this(id,sessionId,revision,trustedIntent,latestUserInput,createdAt,updatedAt,null);
    }
    public GoalRecord appendUser(String input, long now) {
        return appendUser(input,now,null);
    }
    public GoalRecord appendUser(String input,long now,String inputTaskId){
        if (input == null || input.isBlank() || input.length() > 10000) throw new IllegalArgumentException("用户消息无效");
        String combined = trustedIntent + "\n用户后续表达：" + input;
        if (combined.length() > 24000) throw new IllegalStateException("目标上下文已满，请开启新的对话");
        return new GoalRecord(id,sessionId,revision+1,combined,input,createdAt,now,inputTaskId);
    }
}
