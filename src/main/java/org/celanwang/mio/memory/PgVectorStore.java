package org.celanwang.mio.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link VectorStore} 的 PG 实现（app.memory.store=pg 时启用）：向量落 embeddings 表，
 * 检索用 pgvector 余弦距离（{@code <=>}，score = 1 - 距离即余弦相似度）。
 * 不引 pgvector-java 客户端：向量以 '[1,2,3]'::vector 字面量写入。
 * 失败方向 fail-open：PG 不可达时检索返回空、contains 返回 false、写入只 warn。
 */
@Component
@ConditionalOnProperty(name = "app.memory.store", havingValue = "pg")
public class PgVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    private final JdbcTemplate jdbc;

    public PgVectorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(String id, float[] vector) {
        try {
            jdbc.update("""
                    INSERT INTO embeddings(id, embedding) VALUES (?, ?::vector)
                    ON CONFLICT (id) DO UPDATE SET embedding = EXCLUDED.embedding
                    """, id, toVectorLiteral(vector));
        } catch (Exception e) {
            log.warn("向量写入失败（fail-open，跳过）：{}", e.getMessage());
        }
    }

    @Override
    public List<ScoredId> search(float[] query, int topK) {
        try {
            String literal = toVectorLiteral(query);
            return jdbc.query("""
                    SELECT id, 1 - (embedding <=> ?::vector) AS score
                    FROM embeddings
                    ORDER BY embedding <=> ?::vector
                    LIMIT ?
                    """, (rs, rowNum) -> new ScoredId(rs.getString("id"), rs.getDouble("score")),
                    literal, literal, topK);
        } catch (Exception e) {
            log.warn("向量检索失败（fail-open，按无命中处理）：{}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean contains(String id) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM embeddings WHERE id = ?", Integer.class, id);
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("向量查询失败（fail-open，按不存在处理）：{}", e.getMessage());
            return false;
        }
    }

    @Override
    public int size() {
        try {
            Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM embeddings", Integer.class);
            return count == null ? 0 : count;
        } catch (Exception e) {
            log.warn("向量计数失败（fail-open，按空处理）：{}", e.getMessage());
            return 0;
        }
    }

    /** pgvector 字面量：'[0.1,0.2,...]'，配合 ::vector 转换入库。 */
    static String toVectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(vector[i]);
        }
        return literal.append(']').toString();
    }
}
