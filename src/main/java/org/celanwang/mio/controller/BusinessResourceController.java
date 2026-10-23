package org.celanwang.mio.controller;
import org.celanwang.mio.goals.JdbcBusinessResources;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/goals/{session}/resources")
@CrossOrigin(origins={"http://localhost:63342","http://127.0.0.1:63342"})
@ConditionalOnProperty(prefix="app.multi-agent",name="enabled",havingValue="true")
public class BusinessResourceController {
    private final JdbcBusinessResources resources;
    public BusinessResourceController(JdbcBusinessResources resources){this.resources=resources;}
    public record Watch(boolean enabled){}
    @PostMapping("/{operation}/watch") public void watch(@PathVariable String session,@PathVariable String operation,@RequestBody Watch request){resources.watch(session,operation,request.enabled());}
    @GetMapping public List<Map<String,Object>> list(@PathVariable String session){return resources.list(session);}
    @GetMapping("/{operation}/history") public List<Map<String,Object>> history(@PathVariable String session,@PathVariable String operation){return resources.history(session,operation);}
    @PostMapping("/{operation}/observations") public void report(@PathVariable String session,@PathVariable String operation,@RequestBody JdbcBusinessResources.Report report){resources.report(session,operation,report);}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class}) public org.springframework.http.ResponseEntity<Map<String,String>> error(RuntimeException e){return org.springframework.http.ResponseEntity.status(e instanceof IllegalArgumentException?400:409).body(Map.of("message",e.getMessage()));}
}
