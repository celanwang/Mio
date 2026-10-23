package org.celanwang.mio.controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
@RestController @RequestMapping("/api/knowledge/episodes")
@CrossOrigin(origins={"http://localhost:63342","http://127.0.0.1:63342"})
public class MemoryRetentionController {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public MemoryRetentionController(JdbcTemplate jdbc,DataSource source){this.jdbc=jdbc;tx=new TransactionTemplate(new DataSourceTransactionManager(source));}
    @GetMapping public List<Map<String,Object>> list(@RequestParam String session,@RequestParam(defaultValue="") String after){return jdbc.queryForList("SELECT id,role,ts,text,md5(text) AS source_hash FROM messages WHERE session_id=? AND id>? ORDER BY id LIMIT 50",session,after);}
    @DeleteMapping("/{id}") public Map<String,String> forget(@PathVariable String id,@RequestParam String session,@RequestParam String expectedHash){return tx.execute(status->{
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('mio-episode'),hashtext(?))",Object.class,id);
        var row=jdbc.queryForList("SELECT md5(text) AS source_hash FROM messages WHERE id=? AND session_id=? FOR UPDATE",id,session);if(row.size()!=1||!row.get(0).get("source_hash").equals(expectedHash))throw new IllegalArgumentException("原文不存在或已改变");
        jdbc.update("INSERT INTO forgotten_episodes VALUES (?,?) ON CONFLICT DO NOTHING",id,System.currentTimeMillis());
        jdbc.update("DELETE FROM memory_jobs WHERE message_id=?",id);jdbc.update("DELETE FROM episode_vectors WHERE id=?",id);jdbc.update("DELETE FROM embeddings WHERE id=?",id);jdbc.update("DELETE FROM entities WHERE source_message_id=?",id);jdbc.update("DELETE FROM messages WHERE id=?",id);
        jdbc.update("INSERT INTO events(id,ts,session_id,node,type,title,detail) VALUES (?,?,?,'knowledge','episode_forgotten','episode_forgotten',?)",UUID.randomUUID().toString(),System.currentTimeMillis(),session,id);
        return Map.of("scope","EPISODE_AND_DERIVED_INDEX","retained","画像、SDK上下文、目标历史、业务证据和用量保留，需按各自范围管理");
    });}
}
