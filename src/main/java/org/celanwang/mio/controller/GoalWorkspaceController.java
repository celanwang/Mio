package org.celanwang.mio.controller;
import org.celanwang.mio.goals.*;
import org.celanwang.mio.orchestration.MultiAgentChatService;
import org.celanwang.mio.model.dto.ChatRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import reactor.core.publisher.Mono;
import java.util.*;
@RestController @RequestMapping("/api/workspace/goals")
@CrossOrigin(origins={"http://localhost:63342","http://127.0.0.1:63342"})
@ConditionalOnProperty(prefix="app.multi-agent",name="enabled",havingValue="true")
public class GoalWorkspaceController {
    private final GoalWorkspace workspace;private final GoalPlanGenerator planner;private final MultiAgentChatService chat;
    public GoalWorkspaceController(GoalWorkspace workspace,GoalPlanGenerator planner,MultiAgentChatService chat){this.workspace=workspace;this.planner=planner;this.chat=chat;}
    public record Create(String intent){} public record Ack(long version){} public record Answer(String taskId,String questionId,String text){}
    @GetMapping public List<Map<String,Object>> list(){return workspace.list();}
    @PostMapping public Map<String,String> create(@RequestBody Create request){return Map.of("sessionId",workspace.create(request.intent()));}
    @GetMapping("/{session}") public Map<String,Object> snapshot(@PathVariable String session){return workspace.snapshot(session);}
    @PostMapping("/{session}/plan") public Mono<Void> plan(@PathVariable String session){return planner.generate(session).doOnSuccess(ignored->workspace.recordProgress(session));}
    @PostMapping("/{session}/answer") public Mono<Void> answer(@PathVariable String session,@RequestBody Answer answer){var request=new ChatRequest();request.setSessionId(session);request.setMessage(answer.text());request.setTaskReply(new ChatRequest.TaskReply(answer.taskId(),answer.questionId()));return chat.chat(request).doOnSuccess(value->workspace.recordProgress(session)).then();}
    @PostMapping("/{session}/continue/{task}") public Mono<Void> resume(@PathVariable String session,@PathVariable String task){var request=new ChatRequest();request.setSessionId(session);request.setResumeTaskId(task);return chat.chat(request).doOnSuccess(value->workspace.recordProgress(session)).then();}
    @PostMapping("/{session}/deliveries") public Map<String,Object> deliver(@PathVariable String session){return workspace.deliver(session);}
    @GetMapping("/{session}/deliveries/{id}") public Map<String,Object> delivery(@PathVariable String session,@PathVariable String id){return workspace.delivery(session,id);}
    @PostMapping("/{session}/deliveries/{id}/acknowledge") public void acknowledge(@PathVariable String session,@PathVariable String id,@RequestBody Ack request){workspace.acknowledge(session,id,request.version());}
    @PostMapping("/{session}/notifications/{id}/read") public void read(@PathVariable String session,@PathVariable String id){workspace.readNotification(session,id);}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,org.springframework.dao.DataAccessException.class}) public org.springframework.http.ResponseEntity<Map<String,String>> error(Exception e){return org.springframework.http.ResponseEntity.status(e instanceof IllegalArgumentException?400:e instanceof org.springframework.dao.DataAccessException?503:409).body(Map.of("message",e instanceof org.springframework.dao.DataAccessException?"工作台存储暂不可用":e.getMessage()));}
}
