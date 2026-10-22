package org.celanwang.mio.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link EpisodeStore} 的 PG 实现（唯一实现）：episode 原文落 messages 表，
 * 按 id 幂等（ON CONFLICT DO NOTHING）。与向量同库，保证向量引用的原文一定可取到。
 * best-effort：写失败只 warn，读失败返回空。
 */
@Component
public class PgEpisodeStore implements EpisodeStore {

    private static final Logger log = LoggerFactory.getLogger(PgEpisodeStore.class);

    private final JdbcTemplate jdbc;

    public PgEpisodeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AppendResult append(Episode episode) {
        try {
            int inserted = jdbc.update("""
                    INSERT INTO messages(id, session_id, ts, role, text) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """, episode.id(), episode.sessionId(), episode.timestamp(),
                    episode.role(), episode.text());
            if (inserted == 1) return AppendResult.INSERTED;
            Integer same = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM messages WHERE id = ? AND session_id IS NOT DISTINCT FROM ?
                    AND role = ? AND text = ?
                    """, Integer.class, episode.id(), episode.sessionId(), episode.role(), episode.text());
            if (Integer.valueOf(1).equals(same)) return AppendResult.EXISTING;
            log.warn("会话消息身份冲突，保留原文且不建立派生索引");
        } catch (Exception e) {
            log.warn("会话消息落库失败（不影响主流程）：{}", e.getMessage());
        }
        return AppendResult.FAILED;
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
