package org.celanwang.mio.memory;

import java.util.List;

/**
 * 向量存储接缝：episode 向量的存取与相似检索。
 * 唯一实现为 pgvector（{@link PgVectorStore}）；
 * 向量数超过约 5 万条时按接缝替换为 Lucene HNSW 或其他实现，调用方不感知。
 */
public interface VectorStore {

    /** 写入/覆盖一条向量并持久化。 */
    void upsert(String id, float[] vector);

    /** Durable workers require write failures to propagate so their job cannot be acknowledged early. */
    void upsertStrict(String id,float[] vector);
    boolean containsStrict(String id);

    /** 余弦相似度 top-k 检索，按分数降序。 */
    List<ScoredId> search(float[] query, int topK);

    boolean contains(String id);

    int size();

    /** 一条检索命中。 */
    record ScoredId(String id, double score) {
    }
}
