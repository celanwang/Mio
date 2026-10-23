package org.celanwang.mio.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Collections;
import org.celanwang.mio.memory.jobs.JdbcMemoryJobs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * {@link EpisodeStore} 的 PG 实现（唯一实现）：episode 原文落 messages 表，
 * 按 id 幂等（ON CONFLICT DO NOTHING）。与向量同库，保证向量引用的原文一定可取到。
 * best-effort：写失败只 warn，读失败返回空。
 */
@Component
public class PgEpisodeStore implements EpisodeStore {

    private static final Logger log = LoggerFactory.getLogger(PgEpisodeStore.class);

    private final JdbcTemplate jdbc;
    private final JdbcMemoryJobs jobs;
    private final boolean index,distill;

    @Autowired
    public PgEpisodeStore(JdbcTemplate jdbc,JdbcMemoryJobs jobs,@Value("${app.memory.rag.enabled:true}")boolean index,@Value("${app.memory.distiller.enabled:true}")boolean distill) {
        this.jdbc = jdbc;this.jobs=jobs;this.index=index;this.distill=distill;
    }

    @Override
    public AppendResult append(Episode episode) {
        try {
            return jobs.append(episode,index,distill);
        } catch (Exception e) {
            log.warn("会话消息与派生任务登记失败（不影响主流程）：{}", e.getClass().getSimpleName());
        }
        return AppendResult.FAILED;
    }

    @Override public Optional<Episode> find(String id){return jdbc.query("SELECT id,session_id,ts,role,text FROM messages WHERE id=?",(r,n)->new Episode(r.getString(1),r.getString(2),r.getLong(3),r.getString(4),r.getString(5)),id).stream().findFirst();}

    @Override public List<Episode> findByIds(List<String> ids){
        if(ids.isEmpty())return List.of();if(ids.size()>100)throw new IllegalArgumentException("消息回源数量过多");
        try{return jdbc.query("SELECT id,session_id,ts,role,text FROM messages WHERE id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",(r,n)->new Episode(r.getString(1),r.getString(2),r.getLong(3),r.getString(4),r.getString(5)),ids.toArray());}
        catch(Exception unavailable){log.warn("召回原文读取失败，按无结果处理");return List.of();}
    }

    @Override
    public List<Episode> listAll() {
        try {
            return jdbc.query("SELECT id, session_id, ts, role, text FROM messages ORDER BY ts",
                    (rs, rowNum) -> new Episode(rs.getString("id"), rs.getString("session_id"),
                            rs.getLong("ts"), rs.getString("role"), rs.getString("text")));
        } catch (Exception e) {
            log.warn("messages 查询失败（fail-open，按空处理）：{}", e.getMessage());
            return List.of();
        }
    }
}
