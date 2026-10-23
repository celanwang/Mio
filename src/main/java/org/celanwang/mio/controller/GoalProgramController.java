package org.celanwang.mio.controller;

import org.celanwang.mio.goals.*;
import org.celanwang.mio.orchestration.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;

@RestController
@RequestMapping("/api/goals")
@ConditionalOnProperty(prefix="app.multi-agent",name="enabled",havingValue="true")
public class GoalProgramController {
    private final JdbcGoalPrograms programs;private final AgentRegistry registry;
    public GoalProgramController(JdbcGoalPrograms programs,AgentRegistry registry){this.programs=programs;this.registry=registry;}
    @GetMapping("/{session}/program") public Map<String,Object> view(@PathVariable String session){var result=programs.view(session);result.put("capabilities",registry.discover());return result;}
    @PutMapping("/{session}/program") public GoalProgram save(@PathVariable String session,@RequestBody GoalProgram.Request request){return programs.save(session,request);}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,org.springframework.dao.DataAccessException.class})
    ResponseEntity<Map<String,String>> error(Exception error){return ResponseEntity.status(error instanceof IllegalArgumentException?HttpStatus.BAD_REQUEST:error instanceof org.springframework.dao.DataAccessException?HttpStatus.SERVICE_UNAVAILABLE:HttpStatus.CONFLICT).body(Map.of("message",error instanceof org.springframework.dao.DataAccessException?"目标存储暂不可用":error.getMessage()));}
}
