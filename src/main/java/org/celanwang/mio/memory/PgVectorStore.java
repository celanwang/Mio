package org.celanwang.mio.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link VectorStore} 的 PG 实现（唯一实现）：向量落 embeddings 表，
 * 检索用 pgvector 余弦距离（{@code <=>}，score = 1 - 距离即余弦相似度）。
 * 不引 pgvector-java 客户端：向量以 '[1,2,3]'::public.vector 字面量写入。
 * 失败方向 fail-open：PG 不可达时检索返回空、contains 返回 false、写入只 warn。
 */
@Component
public class PgVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    private final JdbcTemplate jdbc;
    private final String namespace;
    @org.springframework.beans.factory.annotation.Autowired public PgVectorStore(JdbcTemplate jdbc,EmbeddingClient embedding){this.jdbc=jdbc;this.namespace=embedding.namespace();}
    @Override public String namespace(){return namespace==null?"legacy":namespace;}
    public static void initializeSchema(JdbcTemplate jdbc){jdbc.execute("CREATE TABLE IF NOT EXISTS episode_vectors(namespace TEXT NOT NULL,id TEXT NOT NULL REFERENCES messages(id),embedding vector(1024) NOT NULL,source_hash TEXT NOT NULL,PRIMARY KEY(namespace,id))");}

    public PgVectorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;this.namespace=null;
    }

    @Override
    public void upsert(String id, float[] vector) {
        try {
            upsertStrict(id,vector);
        } catch (Exception e) {
            log.warn("向量写入失败（fail-open，跳过）：{}", e.getMessage());
        }
    }

    @Override public void upsertStrict(String id,float[] vector){
        if(vector==null||vector.length!=1024)throw new IllegalArgumentException("episode 向量维度必须为 1024");
        double norm=0;for(float value:vector){if(!Float.isFinite(value))throw new IllegalArgumentException("向量不能含非有限值");norm+=(double)value*value;}
        if(norm==0)throw new IllegalArgumentException("向量不能为零向量");
        if(namespace!=null){if(jdbc.update("INSERT INTO episode_vectors(namespace,id,embedding,source_hash) SELECT ?,id,?::public.vector,md5(text) FROM messages WHERE id=? ON CONFLICT(namespace,id) DO UPDATE SET embedding=EXCLUDED.embedding,source_hash=EXCLUDED.source_hash",namespace,toVectorLiteral(vector),id)!=1)throw new IllegalArgumentException("原文不存在");return;}
        jdbc.update("INSERT INTO embeddings(id,embedding) VALUES (?,?::public.vector) ON CONFLICT(id) DO UPDATE SET embedding=EXCLUDED.embedding",id,toVectorLiteral(vector));
    }
    @Override public boolean containsStrict(String id){return namespace==null?Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM embeddings WHERE id=?)",Boolean.class,id)):Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM episode_vectors v JOIN messages m ON m.id=v.id WHERE v.namespace=? AND v.id=? AND v.source_hash=md5(m.text))",Boolean.class,namespace,id));}

    @Override
    public List<ScoredId> search(float[] query, int topK) {
        try {
            String literal = toVectorLiteral(query);
            if(namespace!=null)return jdbc.query("SELECT v.id,1-(v.embedding<=>?::public.vector) AS score FROM episode_vectors v JOIN messages m ON m.id=v.id WHERE v.namespace=? AND v.source_hash=md5(m.text) ORDER BY v.embedding<=>?::public.vector LIMIT ?",(r,n)->new ScoredId(r.getString(1),r.getDouble(2)),literal,namespace,literal,topK);
            return jdbc.query("""
                    SELECT id, 1 - (embedding <=> ?::public.vector) AS score
                    FROM embeddings
                    ORDER BY embedding <=> ?::public.vector
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
            if(namespace!=null)return containsStrict(id);
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
            Integer count = namespace==null?jdbc.queryForObject("SELECT COUNT(1) FROM embeddings", Integer.class):jdbc.queryForObject("SELECT COUNT(*) FROM episode_vectors WHERE namespace=?",Integer.class,namespace);
            return count == null ? 0 : count;
        } catch (Exception e) {
            log.warn("向量计数失败（fail-open，按空处理）：{}", e.getMessage());
            return 0;
        }
    }

    /** pgvector 字面量：'[0.1,0.2,...]'，配合 ::public.vector 转换入库。 */
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
