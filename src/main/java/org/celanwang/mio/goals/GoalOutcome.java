package org.celanwang.mio.goals;

import java.util.*;

/** Explain deterministic progress without equating finished reports with business fulfillment. */
public record GoalOutcome(String conclusion,List<String> gaps,List<String> nextSteps) {
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
