package org.celanwang.mio.controller;

import org.celanwang.mio.memory.LocalMemoryTaskExecutor;
import org.celanwang.mio.memory.jobs.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Local-only read diagnostics: never returns raw messages, model checkpoints or credential details. */
@RestController
@RequestMapping("/api/memory/jobs")
public class MemoryJobsController {
    private final JdbcMemoryJobs jobs;private final JdbcMemoryUsage usage;private final MemoryJobDispatcher dispatcher;private final LocalMemoryTaskExecutor transientTasks;
    public MemoryJobsController(JdbcMemoryJobs jobs,JdbcMemoryUsage usage,MemoryJobDispatcher dispatcher,LocalMemoryTaskExecutor transientTasks){this.jobs=jobs;this.usage=usage;this.dispatcher=dispatcher;this.transientTasks=transientTasks;}
    @GetMapping public Map<String,Object> diagnostics(){return Map.of("jobs",jobs.diagnostics(),"billing",usage.diagnostics(),"worker",dispatcher.diagnostics(),"cacheTasks",transientTasks.diagnostics());}
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,IllegalStateException.class})
    public ResponseEntity<Map<String,String>> unavailable(){return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message","记忆后台状态暂不可用，不能按零积压或零用量处理。"));}
}
