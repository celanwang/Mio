package org.celanwang.mio.controller;
import org.celanwang.mio.goals.GoalSourceReferences;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/workspace/goals/{session}/sources")
@CrossOrigin(origins={"http://localhost:63342","http://127.0.0.1:63342"})
@ConditionalOnProperty(prefix="app.multi-agent",name="enabled",havingValue="true")
public class GoalSourceController {
    private final GoalSourceReferences sources;public GoalSourceController(GoalSourceReferences sources){this.sources=sources;}
    @GetMapping public List<Map<String,Object>> list(@PathVariable String session){return sources.list(session);}
    @PostMapping public void add(@PathVariable String session,@RequestBody GoalSourceReferences.Add request){sources.add(session,request);}
    @DeleteMapping("/{id}") public void remove(@PathVariable String session,@PathVariable String id){sources.remove(session,id);}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,org.springframework.dao.DataAccessException.class}) public org.springframework.http.ResponseEntity<Map<String,String>> error(Exception e){return org.springframework.http.ResponseEntity.status(e instanceof IllegalArgumentException?400:e instanceof org.springframework.dao.DataAccessException?503:409).body(Map.of("message",e instanceof org.springframework.dao.DataAccessException?"论据存储暂不可用":e.getMessage()));}
}
