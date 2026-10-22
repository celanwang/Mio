package org.celanwang.mio.controller;

import org.celanwang.mio.orchestration.*;
import org.celanwang.mio.model.vo.ChatResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.*;
import java.util.Map;

/** Existing local-only interceptor covers this API. Decisions still travel through /chat and its broker. */
@RestController
@RequestMapping("/api/tasks")
@CrossOrigin(origins={"http://localhost:63342","http://127.0.0.1:63342"})
@ConditionalOnProperty(prefix="app.multi-agent",name="enabled",havingValue="true")
public class TaskController {
    private final TaskRuntime runtime;private final MultiAgentChatService chat;private final GoalLifecycle lifecycle;private final JdbcGoalAcceptance acceptance;
    public TaskController(TaskRuntime runtime,MultiAgentChatService chat,GoalLifecycle lifecycle,JdbcGoalAcceptance acceptance){this.runtime=runtime;this.chat=chat;this.lifecycle=lifecycle;this.acceptance=acceptance;}
    @GetMapping public ChatResponse list(@RequestParam String sessionId){return chat.response(sessionId,"");}
    public record GoalRequest(String sessionId,Long expectedControlRevision,GoalRecord.State state){}
    @PostMapping("/goal") public ChatResponse goal(@RequestBody GoalRequest request){
        if(request==null||request.expectedControlRevision()==null)throw new IllegalArgumentException("缺少目标控制版本");
        lifecycle.transition(request.sessionId(),request.expectedControlRevision(),request.state());return chat.response(request.sessionId(),"目标状态已更新；预算、历史与待核查操作保留。");
    }
    @PutMapping("/goal/acceptance") public ChatResponse acceptance(@RequestBody GoalAcceptance.Request request){
        if(request==null)throw new IllegalArgumentException("缺少验收条件");
        acceptance.confirm(request);return chat.response(request.sessionId(),"验收条件已确认；这不会批准或执行业务操作。");
    }
    @PostMapping("/{id}/operations/{operationId}/reconcile") public reactor.core.publisher.Mono<ChatResponse> reconcile(@PathVariable String id,@PathVariable String operationId,@RequestBody CancelRequest request){
        if(request==null||request.sessionId()==null)throw new IllegalArgumentException("缺少会话标识");
        return chat.reconcile(request.sessionId(),id,operationId);
    }
    public record CancelRequest(String sessionId){}
    @PostMapping("/{id}/cancel") public ChatResponse cancel(@PathVariable String id,@RequestBody CancelRequest request){
        if(request==null||request.sessionId()==null)throw new IllegalArgumentException("缺少会话标识");
        runtime.cancel(request.sessionId(),id);return chat.response(request.sessionId(),"任务状态已更新。已发出的业务请求仍需核查。");
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,String>> malformedRequest(){return ResponseEntity.badRequest().body(Map.of("message","请求参数不完整或格式无效，请刷新后重试。"));}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,org.springframework.dao.DataAccessException.class})
    public ResponseEntity<Map<String,String>> error(Exception error){
        if(error instanceof RunBudget.BudgetExceeded)return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("message",error.getMessage()));
        if(error instanceof org.springframework.dao.DataAccessException)return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message","协作存储不可用，未继续执行"));
        return ResponseEntity.status(error instanceof IllegalArgumentException?HttpStatus.BAD_REQUEST:HttpStatus.CONFLICT).body(Map.of("message",error.getMessage()));
    }
}
