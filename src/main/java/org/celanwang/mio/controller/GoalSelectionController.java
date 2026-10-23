package org.celanwang.mio.controller;

import org.celanwang.mio.goals.GoalSelections;
import org.celanwang.mio.orchestration.MultiAgentChatService;
import org.celanwang.mio.model.dto.ChatRequest;
import org.celanwang.mio.model.vo.ChatResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.*;

@RestController @RequestMapping("/api/workspace/goals/{session}/selections")
@CrossOrigin(origins={"http://localhost:63342","http://127.0.0.1:63342"})
@ConditionalOnProperty(prefix="app.multi-agent",name="enabled",havingValue="true")
public class GoalSelectionController {
    private final GoalSelections selections;private final MultiAgentChatService chat;
    public GoalSelectionController(GoalSelections selections,MultiAgentChatService chat){this.selections=selections;this.chat=chat;}
    @GetMapping public List<Map<String,Object>> list(@PathVariable String session){return selections.list(session);}
    @PostMapping public Map<String,Object> choose(@PathVariable String session,@RequestBody GoalSelections.Choose request){return selections.choose(session,request);}
    @PostMapping("/{id}/execute") public Mono<ChatResponse> execute(@PathVariable String session,@PathVariable String id){return selections.execute(session,id).then(Mono.fromSupplier(()->chat.response(session,"已登记本次选择的执行请求；具体业务调用仍需确认。")));}
    public record Decisions(List<ChatRequest.Confirmation> confirmations){}
    @PostMapping("/{id}/approve") public Mono<ChatResponse> approve(@PathVariable String session,@PathVariable String id,@RequestBody Decisions request){return selections.approve(session,id,request.confirmations()).then(Mono.fromSupplier(()->chat.response(session,"本次操作决定已处理，请查看回执与任务状态。")));}
    @PostMapping("/{id}/feedback") public void feedback(@PathVariable String session,@PathVariable String id,@RequestBody GoalSelections.Feedback request){selections.feedback(session,id,request);}
    @GetMapping("/feedback") public List<Map<String,Object>> feedback(@PathVariable String session){return selections.feedback(session);}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,org.springframework.dao.DataAccessException.class})
    public org.springframework.http.ResponseEntity<Map<String,String>> error(Exception e){return org.springframework.http.ResponseEntity.status(e instanceof IllegalArgumentException?400:e instanceof org.springframework.dao.DataAccessException?503:409).body(Map.of("message",e instanceof org.springframework.dao.DataAccessException?"选择记录暂不可用":e.getMessage()));}
}
