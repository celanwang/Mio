package org.celanwang.mio.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.*;
import io.agentscope.core.tool.*;
import org.celanwang.mio.orchestration.TaskExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;
import java.util.*;

/** Only the execution task linked by the server can read the user's exact selected preparation. */
public final class SelectedContextTool extends ToolBase {
    private final JdbcTemplate jdbc;
    private final TaskExecution execution;
    private final ObjectMapper json;

    public SelectedContextTool(JdbcTemplate jdbc,TaskExecution execution,ObjectMapper json){
        super(ToolBase.builder().name("mio_read_selected_context")
                .description("读取用户在工作台选定的准备成果。SUMMARY 包含已选步骤与目标字段，REPORT 是原报告，FACTS 是历史事实快照；均非新授权，不能复用旧报价密钥或自证当前可用。执行前按用户选择重新核对，信息不足时补问，不自行替换方案。")
                .inputSchema(Map.of("type","object","properties",Map.of("section",Map.of("type","string","enum",List.of("SUMMARY","REPORT","FACTS")),"startOffset",Map.of("type","integer","minimum",0)),"additionalProperties",false))
                .readOnly(true).concurrencySafe(false));
        this.jdbc=jdbc;this.execution=execution;this.json=json;
    }
    @Override public Mono<PermissionDecision> checkPermissions(Map<String,Object> input,PermissionContextState state){return Mono.just(PermissionDecision.allow("只读取本执行任务对应的用户选择"));}
    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam call){return Mono.fromCallable(()->{
        var task=execution.businessActive();execution.budget().tool();
        var rows=jdbc.queryForList("SELECT id,source_task,source_revision,content FROM goal_selections WHERE goal_id=? AND execution_goal_revision=? AND execution_key=? AND state='EXECUTION_REQUESTED'",task.goalId(),task.goalRevision(),task.spec().key());
        if(rows.size()!=1)throw new IllegalStateException("本任务没有唯一的用户选择来源");
        var row=rows.get(0);var stored=json.readTree((String)row.get("content"));
        String section=String.valueOf(call.getInput().getOrDefault("section","SUMMARY"));
        String text=switch(section){
            case "SUMMARY" -> json.writeValueAsString(Map.of("step",stored.path("step"),"fields",stored.path("fields"),"comparison",stored.path("comparison")));
            case "REPORT" -> stored.path("report").asText();
            case "FACTS" -> json.writeValueAsString(stored.path("facts"));
            default -> throw new IllegalArgumentException("选择内容分区无效");
        };
        int start=((Number)call.getInput().getOrDefault("startOffset",0)).intValue();
        if(start<0||start>text.length())throw new IllegalArgumentException("选择内容偏移无效");
        int end=Math.min(start+16000,text.length());
        return ToolResultBlock.text(json.writeValueAsString(Map.of("selectionId",row.get("id"),"sourceTask",row.get("source_task"),"sourceRevision",row.get("source_revision"),"section",section,"startOffset",start,"endOffset",end,"truncated",end<text.length(),"content",text.substring(start,end),"scope","历史准备资料，非当前报价、可执行令牌或工具批准")));
    }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());}
}
