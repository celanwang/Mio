package org.celanwang.mio.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.message.ToolResultBlock;
import org.celanwang.mio.orchestration.RunBudget;
import reactor.core.publisher.Mono;
import java.util.*;

/** Proposal only; confirmation and background policy are owned by the user-facing API. */
public final class GoalProgramTool extends ToolBase {
    private final JdbcGoalPrograms programs;private final String session;private final RunBudget budget;private final ObjectMapper json;
    public GoalProgramTool(JdbcGoalPrograms programs,String session,RunBudget budget,ObjectMapper json){
        super(ToolBase.builder().name("propose_goal_program").description("为跨轮目标提出准备计划，供用户在持续目标页面确认。fields 是用户目标字段；steps 含 id、capabilityId、objective、fields、dependencies、outputContract。不启动任务，不批准业务操作。")
                .inputSchema(Map.of("type","object","properties",Map.of("fields",Map.of("type","object","additionalProperties",Map.of("type","string")),"steps",Map.of("type","array","maxItems",12,"items",Map.of("type","object","properties",Map.of("id",Map.of("type","string"),"capabilityId",Map.of("type","string"),"objective",Map.of("type","string"),"fields",Map.of("type","array","items",Map.of("type","string")),"dependencies",Map.of("type","array","items",Map.of("type","string")),"outputContract",Map.of("type","string")),"required",List.of("id","capabilityId","objective"),"additionalProperties",false))),"required",List.of("fields","steps"),"additionalProperties",false)).readOnly(false).concurrencySafe(false));
        this.programs=programs;this.session=session;this.budget=budget;this.json=json;
    }
    @Override public Mono<PermissionDecision> checkPermissions(Map<String,Object> input,PermissionContextState state){return Mono.just(PermissionDecision.allow("仅登记未采用的计划建议"));}
    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam call){return Mono.fromSupplier(()->{
        budget.tool();return budget.fenced(()->{
            var view=programs.view(session);var goal=(org.celanwang.mio.orchestration.GoalRecord)view.get("goal");var current=(GoalProgram)view.get("program");
            var fields=json.convertValue(call.getInput().get("fields"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){});
            var steps=json.convertValue(call.getInput().get("steps"),new com.fasterxml.jackson.core.type.TypeReference<List<GoalProgram.Step>>(){});
            var caps=new HashSet<String>();steps.forEach(s->caps.add(s.capabilityId()));
            programs.propose(session,new GoalProgram.Request(current==null?0:current.revision(),goal.revision(),fields,steps,new GoalProgram.Policy(false,System.currentTimeMillis()+3600000,10,300,1,caps)));
            return ToolResultBlock.text("计划建议已保存。请用户打开持续目标页面审阅并保存；当前未启动后台执行。");
        });
    });}
}
