package org.celanwang.mio.controller;

import org.celanwang.mio.memory.PersonalContext;
import org.celanwang.mio.orchestration.TaskRuntime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/workspace/goals/{session}/preferences")
@CrossOrigin(origins={"http://localhost:63342","http://127.0.0.1:63342"})
@ConditionalOnProperty(prefix="app.multi-agent",name="enabled",havingValue="true")
public class PersonalContextController {
    private final PersonalContext personal;private final TaskRuntime runtime;private final org.celanwang.mio.goals.JdbcGoalPrograms programs;private final org.celanwang.mio.orchestration.AgentRegistry registry;
    public PersonalContextController(PersonalContext personal,TaskRuntime runtime,org.celanwang.mio.goals.JdbcGoalPrograms programs,org.celanwang.mio.orchestration.AgentRegistry registry){this.personal=personal;this.runtime=runtime;this.programs=programs;this.registry=registry;}
    @GetMapping public Map<String,Object> view(@PathVariable String session){var goal=runtime.goal(session);var p=programs.find(goal.id()).orElse(null);return personal.capture(session,p==null?Set.of():p.steps().stream().map(s->registry.require(s.capabilityId()).skill()).collect(java.util.stream.Collectors.toSet()),goal.trustedIntent());}
    public record ExceptionRequest(String profileId,boolean excluded,String reason){}
    @PostMapping public void exception(@PathVariable String session,@RequestBody ExceptionRequest request){personal.exclude(session,request.profileId(),request.excluded(),request.reason());}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,org.springframework.dao.DataAccessException.class}) public org.springframework.http.ResponseEntity<Map<String,String>> error(Exception e){return org.springframework.http.ResponseEntity.status(e instanceof IllegalArgumentException?400:e instanceof org.springframework.dao.DataAccessException?503:409).body(Map.of("message",e instanceof org.springframework.dao.DataAccessException?"偏好管理暂不可用":e.getMessage()));}
}
