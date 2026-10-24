package org.celanwang.mio.controller;

import org.celanwang.mio.goals.GoalTriggers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/workspace/goals/{session}/triggers")
@CrossOrigin(origins={"http://localhost:63342","http://127.0.0.1:63342"})
@ConditionalOnProperty(prefix="app.multi-agent",name="enabled",havingValue="true")
public class GoalTriggerController {
    private final GoalTriggers triggers;public GoalTriggerController(GoalTriggers triggers){this.triggers=triggers;}
    @GetMapping public List<Map<String,Object>> list(@PathVariable String session){return triggers.list(session);}
    @PostMapping public Map<String,String> create(@PathVariable String session,@RequestBody GoalTriggers.Request request){return Map.of("id",triggers.create(session,request));}
    public record Pause(long expectedRevision){}
    @PostMapping("/{id}/pause") public void pause(@PathVariable String session,@PathVariable String id,@RequestBody Pause request){triggers.pause(session,id,request.expectedRevision());}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,org.springframework.dao.DataAccessException.class}) public org.springframework.http.ResponseEntity<Map<String,String>> error(Exception e){return org.springframework.http.ResponseEntity.status(e instanceof IllegalArgumentException?400:e instanceof org.springframework.dao.DataAccessException?503:409).body(Map.of("message",e instanceof org.springframework.dao.DataAccessException?"触发器存储暂不可用":e.getMessage()));}
}
