package org.celanwang.mio.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link EpisodeStore} 的 PG 实现（app.memory.store=pg 时启用）：episode 原文落 episodes 表，
 * 按 id 幂等（ON CONFLICT DO NOTHING）。与向量同库，保证向量引用的原文一定可取到。
 * best-effort：写失败只 warn，读失败返回空。
 */
@Component
@ConditionalOnProperty(name = "app.memory.store", havingValue = "pg")
public class PgEpisodeStore implements EpisodeStore {

    private static final Logger log = LoggerFactory.getLogger(PgEpisodeStore.class);

    private final JdbcTemplate jdbc;

    public PgEpisodeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(Episode episode) {
        try {
            jdbc.update("""
                    INSERT INTO episodes(id, ts, role, text) VALUES (?, ?, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """, episode.id(), episode.timestamp(), episode.role(), episode.text());
        } catch (Exception e) {
            log.warn("会话消息落库失败（不影响主流程）：{}", e.getMessage());
        }
    }

    @Override
    public List<Episode> listAll() {
        try {
            return jdbc.query("SELECT id, ts, role, text FROM episodes ORDER BY ts",
                    (rs, rowNum) -> new Episode(rs.getString("id"), rs.getLong("ts"),
                            rs.getString("role"), rs.getString("text")));
        } catch (Exception e) {
            log.warn("episodes 查询失败（fail-open，按空处理）：{}", e.getMessage());
            return List.of();
        }
    }
}
