package org.celanwang.mio.goals;

import java.util.*;

/** Explain deterministic progress without equating finished reports with business fulfillment. */
public record GoalOutcome(String conclusion,List<String> gaps,List<String> nextSteps) {
    public static GoalOutcome execution(org.celanwang.mio.orchestration.TaskSummary task){
        String state=task==null?"EXECUTION_REQUESTED":task.status().name();var gaps=new ArrayList<String>();var next=new ArrayList<String>();
        switch(state){case "AWAITING_APPROVAL" -> {gaps.add("具体业务调用尚待用户批准");next.add("在方案选择与业务执行区域核对参数，逐次批准或拒绝");}case "NEEDS_INPUT","INPUT_READY" -> {gaps.add("执行任务需要补充或继续处理信息");next.add("回答或继续对应执行任务");}case "UNKNOWN" -> {gaps.add("业务结果仍需核查，禁止重复提交");next.add("核查原操作及其关联回执");}case "COMPLETED" -> {gaps.add("任务完成不等于供应商已履约，按资源状态与证据范围判断");next.add("核对回执、跟踪关联资源并记录用户反馈");}case "FAILED","STALE","CANCELLED" -> {gaps.add("执行未形成可直接继续的成功结果");next.add("先核查已有操作，再重新准备新的方案");}default -> next.add("等待执行任务返回；请求已登记时不要重复提交");}
        return new GoalOutcome("已进入独立执行阶段："+state+"。原准备成果保留为来源，新的业务结果以操作回执为准。",gaps,next);
    }
    public static GoalOutcome of(String state,List<Map<String,Object>> steps,List<Map<String,Object>> actions) {
        var gaps=new ArrayList<String>();var next=new LinkedHashSet<String>();
        for(var step:steps) {
            String id=String.valueOf(step.get("id")),status=String.valueOf(step.get("state"));
            if(status.equals("REPORT_READY"))gaps.add(id+"：已完成报告，未由业务事实契约证明结论或履约");
            else if(!status.equals("VERIFIED"))gaps.add(id+"："+status);
        }
        for(var action:actions)next.add(String.valueOf(action.get("text")));
        switch(state){
            case "NO_PLAN","PLAN_STALE" -> {gaps.add("尚无当前用户目标的已确认计划");next.add("生成或修订准备计划，审阅后保存范围");}
            case "EXPIRED","LIMIT_REACHED" -> next.add("在目标接续中明确确认新的期限或额度");
            case "WAITING" -> next.add("推进一次当前已确认的准备计划");
            case "BLOCKED" -> next.add("核对阻塞原因，基于当前证据生成局部计划建议");
            case "PAUSED" -> next.add("确认仍需要继续后恢复目标");
            case "RUNNING" -> next.add("等待当前任务返回，避免重复提交");
            case "UNRESOLVED_OPERATION" -> {gaps.add("存在尚未核实的外部业务操作");next.add("核查原业务操作及供应商状态，不重复提交");}
            case "READY" -> next.add("审阅本次结果、取舍与证据，生成并确认当前准备交付报告");
        }
        return new GoalOutcome(state.equals("READY")?"已确认准备步骤达到各自输出要求；报告质量与业务履约仍需按证据范围判断":"目标仍有待处理内容，当前结果可用于阶段性审阅",List.copyOf(gaps),List.copyOf(next));
    }
}
