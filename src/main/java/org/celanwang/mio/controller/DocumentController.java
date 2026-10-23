package org.celanwang.mio.controller;
import org.celanwang.mio.goals.JdbcDocuments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/documents")
@CrossOrigin(origins={"http://localhost:63342","http://127.0.0.1:63342"})
@ConditionalOnProperty(prefix="app.multi-agent",name="enabled",havingValue="true")
public class DocumentController {
    private final JdbcDocuments documents;public DocumentController(JdbcDocuments documents){this.documents=documents;}
    @GetMapping public List<Map<String,Object>> list(@RequestParam(defaultValue="") String query){return documents.search(query);}
    @GetMapping("/{id}") public JdbcDocuments.Document read(@PathVariable String id){return documents.read(id);}
    @PutMapping("/{id}") public JdbcDocuments.Document save(@PathVariable String id,@RequestBody JdbcDocuments.Save request){return documents.save(id,request,null);}
    public record Publish(String sessionId,String taskId,String title){}
    @PostMapping("/publish") public JdbcDocuments.Document publish(@RequestBody Publish request){return documents.publish(request.sessionId(),request.taskId(),request.title());}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class}) public org.springframework.http.ResponseEntity<Map<String,String>> error(RuntimeException e){return org.springframework.http.ResponseEntity.status(e instanceof IllegalArgumentException?400:409).body(Map.of("message",e.getMessage()));}
}
