package org.celanwang.mio.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * {@link EntityStore} 的 PG 实现（唯一实现）：三元组落 entities 表，
 * 去重语义：同主谓宾且未失效的条目不重复写入。
 * best-effort：写失败只 warn，读失败返回空。
 */
@Component
public class PgEntityStore implements EntityStore {

    private static final Logger log = LoggerFactory.getLogger(PgEntityStore.class);

    private static final String SELECT_VALID =
            "SELECT subject, predicate, object, valid_from, valid_to, source_ts FROM entities";

    private final JdbcTemplate jdbc;

    public PgEntityStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(List<Triple> triples) {
        for (Triple triple : triples) {
            if (triple == null || !StringUtils.hasText(triple.subject())
                    || !StringUtils.hasText(triple.predicate()) || !StringUtils.hasText(triple.object())) {
                continue;
            }
            try {
                Integer exists = jdbc.queryForObject("""
                        SELECT COUNT(1) FROM entities
                        WHERE subject = ? AND predicate = ? AND object = ? AND valid_to IS NULL
                        """, Integer.class, triple.subject(), triple.predicate(), triple.object());
                if (exists != null && exists > 0) {
                    continue;
                }
                jdbc.update("""
                        INSERT INTO entities(subject, predicate, object, valid_from, valid_to, source_ts)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, triple.subject(), triple.predicate(), triple.object(),
                        triple.validFrom(), triple.validTo(), triple.sourceTs());
            } catch (Exception e) {
                log.warn("实体三元组写入失败（fail-open，跳过）：{}", e.getMessage());
            }
        }
    }

    @Override
    public List<Triple> related(String entity) {
        try {
            return jdbc.query(SELECT_VALID + " WHERE subject = ? AND valid_to IS NULL ORDER BY id",
                    this::mapRow, entity);
        } catch (Exception e) {
            log.warn("实体三元组查询失败（fail-open，按空处理）：{}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<Triple> all() {
        try {
            return jdbc.query(SELECT_VALID + " WHERE valid_to IS NULL ORDER BY id", this::mapRow);
        } catch (Exception e) {
            log.warn("实体三元组查询失败（fail-open，按空处理）：{}", e.getMessage());
            return List.of();
        }
    }

    private Triple mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long validTo = rs.getLong("valid_to");
        // wasNull 必须在读 valid_to 之后、读其他列之前调用。
        Long validToOrNull = rs.wasNull() ? null : validTo;
        return new Triple(rs.getString("subject"), rs.getString("predicate"), rs.getString("object"),
                rs.getLong("valid_from"), validToOrNull, rs.getLong("source_ts"));
    }
}
