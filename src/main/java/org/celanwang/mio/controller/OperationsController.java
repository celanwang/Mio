package org.celanwang.mio.controller;
import org.celanwang.mio.ops.ExecutionSettings;
import org.celanwang.mio.orchestration.AgentRegistry;
import org.celanwang.mio.memory.EmbeddingClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/system")
@CrossOrigin(origins={"http://localhost:63342","http://127.0.0.1:63342"})
public class OperationsController {
    private final JdbcTemplate jdbc;private final Environment env;private final ObjectProvider<AgentRegistry> registry;private final EmbeddingClient embedding;
    public OperationsController(JdbcTemplate jdbc,Environment env,ObjectProvider<AgentRegistry> registry,EmbeddingClient embedding){this.jdbc=jdbc;this.env=env;this.registry=registry;this.embedding=embedding;}
    @GetMapping("/readiness") public Map<String,Object> readiness(){
        var result=new LinkedHashMap<String,Object>();result.put("java",System.getProperty("java.version"));result.put("mode",env.getProperty("app.security.local-only",Boolean.class,true)?"LOCAL_SINGLE_USER":"REMOTE_AUTH_REQUIRED");
        result.put("database",query("SELECT 1 AS reachable,current_database() AS database"));result.put("migrations",query("SELECT version,name,applied_at FROM schema_migrations ORDER BY version"));result.put("vectorExtension",query("SELECT extversion FROM pg_extension WHERE extname='vector'"));
        result.put("memoryJobs",query("SELECT kind,state,count(*) FROM memory_jobs GROUP BY kind,state"));result.put("goalWakeups",query("SELECT state,count(*) FROM goal_wakeups GROUP BY state"));
        result.put("models",Map.of("main",env.getProperty("app.dashscope.model-name",""),"domain",env.getProperty("app.dashscope.plus-model-name",""),"embedding",env.getProperty("app.dashscope.embedding-model",""),"namespace",embedding.namespace(),"credentialsConfigured",!env.getProperty("app.dashscope.api-key","").isBlank(),"validation","CONFIGURATION_ONLY"));
        var settings=new ExecutionSettings(env);result.put("limits",Map.of("foreground",settings.foreground(),"background",settings.background(),"planning",settings.planning(),"goal",settings.goal()));
        result.put("goalUsage",query("SELECT coalesce(sum((content::jsonb->>'models')::bigint),0) AS calls,coalesce(sum((content::jsonb->>'knownTokens')::bigint),0) AS known_tokens,coalesce(sum((content::jsonb->>'pendingUsage')::bigint),0) AS pending,coalesce(sum((content::jsonb->>'unknownUsage')::bigint),0) AS unknown FROM goal_budgets"));
        result.put("knowledgeUsage",query("SELECT * FROM memory_usage_days ORDER BY day DESC LIMIT 7"));result.put("usageScope","协作目标与知识向量分别统计，不是供应商总账；未知用量不是零费用");
        var capabilities=registry.getIfAvailable();result.put("capabilities",capabilities==null?List.of():capabilities.discover());return result;
    }
    private Object query(String sql){try{return jdbc.queryForList(sql);}catch(RuntimeException unavailable){return Map.of("state","UNAVAILABLE","code",unavailable.getClass().getSimpleName());}}
}
