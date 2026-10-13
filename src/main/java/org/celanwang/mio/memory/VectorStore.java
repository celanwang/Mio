package org.celanwang.mio.memory;

import java.util.List;

/**
 * 向量存储接缝：episode 向量的存取与相似检索。
 * 实现按 app.memory.store 切换：local（默认）为内存暴力余弦（{@link InMemoryVectorStore}，
 * 万级规模 <10ms，即当前最优），pg 为 pgvector（{@link PgVectorStore}）；
 * 向量数超过约 5 万条时按接缝替换为 Lucene HNSW 或其他实现，调用方不感知。
 */
public interface VectorStore {

    /** 写入/覆盖一条向量并持久化。 */
    void upsert(String id, float[] vector);

    /** 余弦相似度 top-k 检索，按分数降序。 */
    List<ScoredId> search(float[] query, int topK);

    boolean contains(String id);

    int size();

    /** 一条检索命中。 */
    record ScoredId(String id, double score) {
    }
}
