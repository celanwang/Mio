package org.celanwang.mio.controller;

import org.celanwang.mio.goals.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/goals/{session}/comparisons")
@CrossOrigin(origins={"http://localhost:63342","http://127.0.0.1:63342"})
@ConditionalOnProperty(prefix="app.multi-agent",name="enabled",havingValue="true")
public class GoalComparisonController {
    private final GoalComparisons comparisons;
    public GoalComparisonController(GoalComparisons comparisons){this.comparisons=comparisons;}
    @PostMapping public Map<String,Object> compare(@PathVariable String session,@RequestBody GoalComparison.Request request){return comparisons.compare(session,request);}
    @GetMapping public List<Map<String,Object>> recent(@PathVariable String session){return comparisons.recent(session);}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,org.springframework.dao.DataAccessException.class})
    public org.springframework.http.ResponseEntity<Map<String,String>> error(Exception e){return org.springframework.http.ResponseEntity.status(e instanceof IllegalArgumentException?400:e instanceof org.springframework.dao.DataAccessException?503:409).body(Map.of("message",e instanceof org.springframework.dao.DataAccessException?"比较存储暂不可用":e.getMessage()));}
}
