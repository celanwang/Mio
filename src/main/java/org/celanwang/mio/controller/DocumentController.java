package org.celanwang.mio.controller;
import org.celanwang.mio.goals.JdbcDocuments;
import org.celanwang.mio.goals.DocumentIndexJobs;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/documents")
@CrossOrigin(origins={"http://localhost:63342","http://127.0.0.1:63342"})
@ConditionalOnProperty(prefix="app.multi-agent",name="enabled",havingValue="true")
public class DocumentController {
    private final JdbcDocuments documents;private final DocumentIndexJobs jobs;public DocumentController(JdbcDocuments documents,DocumentIndexJobs jobs){this.documents=documents;this.jobs=jobs;}
    @GetMapping public List<Map<String,Object>> list(@RequestParam(defaultValue="") String query){return documents.search(query);}
    @GetMapping("/{id}") public JdbcDocuments.Document read(@PathVariable String id){return documents.read(id);}
    @PutMapping("/{id}") public JdbcDocuments.Document save(@PathVariable String id,@RequestBody JdbcDocuments.Save request){return documents.save(id,request,null);}
    @GetMapping("/{id}/chunks") public List<Map<String,Object>> chunks(@PathVariable String id){return documents.chunks(id);}
    @PostMapping("/{id}/index") public Map<String,Integer> index(@PathVariable String id,@RequestParam long expectedRevision){return Map.of("indexed",documents.index(id,expectedRevision));}
    @GetMapping("/{id}/index-jobs") public List<Map<String,Object>> jobs(@PathVariable String id){return jobs.status(id);}
    @PostMapping("/{id}/index-jobs") public Map<String,Object> start(@PathVariable String id,@RequestParam long expectedRevision){return jobs.start(id,expectedRevision);}
    @PostMapping("/{id}/index-jobs/{job}/pause") public void pause(@PathVariable String id,@PathVariable String job){jobs.pause(id,job);}
    @GetMapping("/{id}/citation") public Map<String,Object> citation(@PathVariable String id,@RequestParam long revision,@RequestParam int start,@RequestParam int end,@RequestParam(required=false) String quote){return documents.citation(id,revision,start,end,quote);}
    @DeleteMapping("/{id}") public void delete(@PathVariable String id,@RequestParam long expectedRevision){documents.delete(id,expectedRevision);}
    public record Publish(String sessionId,String taskId,String title){}
    @PostMapping("/publish") public JdbcDocuments.Document publish(@RequestBody Publish request){return documents.publish(request.sessionId(),request.taskId(),request.title());}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class}) public org.springframework.http.ResponseEntity<Map<String,String>> error(RuntimeException e){return org.springframework.http.ResponseEntity.status(e instanceof IllegalArgumentException?400:409).body(Map.of("message",e.getMessage()));}
}
