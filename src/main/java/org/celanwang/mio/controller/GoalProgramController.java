package org.celanwang.mio.controller;

import org.celanwang.mio.goals.*;
import org.celanwang.mio.orchestration.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;

@RestController
@RequestMapping("/api/goals")
@CrossOrigin(origins={"http://localhost:63342","http://127.0.0.1:63342"})
@ConditionalOnProperty(prefix="app.multi-agent",name="enabled",havingValue="true")
public class GoalProgramController {
    private final JdbcGoalPrograms programs;private final AgentRegistry registry;private final JdbcGoalWakeups wakeups;private final GoalProgramExecutor executor;
    public GoalProgramController(JdbcGoalPrograms programs,AgentRegistry registry,JdbcGoalWakeups wakeups,GoalProgramExecutor executor){this.programs=programs;this.registry=registry;this.wakeups=wakeups;this.executor=executor;}
    @GetMapping("/{session}/program") public Map<String,Object> view(@PathVariable String session){var result=programs.view(session);result.put("capabilities",registry.discover());return result;}
    @PutMapping("/{session}/program") public GoalProgram save(@PathVariable String session,@RequestBody GoalProgram.Request request){return programs.save(session,request);}
    public record WakeRequest(String requestId){}
    @PostMapping("/{session}/wake") public Map<String,String> wake(@PathVariable String session,@RequestBody WakeRequest request){return Map.of("wakeupId",wakeups.wake(session,request.requestId()));}
    @GetMapping("/{session}/status") public Map<String,Object> status(@PathVariable String session){return executor.status(session);}
    public record RefreshRequest(long expectedRevision,Set<String> steps){}
    @PostMapping("/{session}/refresh") public GoalProgram refresh(@PathVariable String session,@RequestBody RefreshRequest request){return programs.refresh(session,request.expectedRevision(),request.steps());}
    @GetMapping("/{session}/compare") public List<Map<String,Object>> compare(@PathVariable String session,@RequestParam String fieldId,@RequestParam(defaultValue="false") boolean descending){return executor.compare(session,fieldId,descending);}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,org.springframework.dao.DataAccessException.class})
    ResponseEntity<Map<String,String>> error(Exception error){return ResponseEntity.status(error instanceof IllegalArgumentException?HttpStatus.BAD_REQUEST:error instanceof org.springframework.dao.DataAccessException?HttpStatus.SERVICE_UNAVAILABLE:HttpStatus.CONFLICT).body(Map.of("message",error instanceof org.springframework.dao.DataAccessException?"目标存储暂不可用":error.getMessage()));}
}
