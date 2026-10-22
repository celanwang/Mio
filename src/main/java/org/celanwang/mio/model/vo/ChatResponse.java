package org.celanwang.mio.model.vo;


import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

@Data
@AllArgsConstructor
public class ChatResponse {

    private String sessionId;

    private String reply;

    private List<ToolConfirmation> pendingConfirmations;

    private WeiboAction weiboAction;

    private java.util.List<org.celanwang.mio.orchestration.TaskSummary> tasks;

    private boolean multiAgent;
    private org.celanwang.mio.orchestration.GoalBudgetStore.Snapshot budget;

    public ChatResponse(String sessionId,String reply,List<ToolConfirmation> pendingConfirmations,WeiboAction weiboAction,
                        java.util.List<org.celanwang.mio.orchestration.TaskSummary> tasks,boolean multiAgent){
        this(sessionId,reply,pendingConfirmations,weiboAction,tasks,multiAgent,null);
    }

    public ChatResponse(String sessionId,String reply,List<ToolConfirmation> pendingConfirmations,WeiboAction weiboAction) {
        this(sessionId,reply,pendingConfirmations,weiboAction,List.of(),false);
    }

}
