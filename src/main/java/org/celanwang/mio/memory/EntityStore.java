package org.celanwang.mio.memory;

import java.util.List;

/**
 * 实体簿接缝：画像相关的实体关系（三元组）存储。
 * 当前实现为本地 JSONL（{@link LocalEntityStore}）；未来出现真实多跳查询需求时
 * 可替换为 PG/图库实现，消费方不感知。
 */
public interface EntityStore {

    /** 追加三元组（已存在相同主谓宾且未失效的条目会去重）。 */
    void append(List<Triple> triples);

    /** 查某个实体的全部有效三元组。 */
    List<Triple> related(String entity);

    /** 全部有效三元组（面板观察用）。 */
    List<Triple> all();

    /**
     * 一条实体关系三元组，带双时态字段（validFrom/validTo），为未来时态查询留地基。
     *
     * @param sourceTs 来源事件时间（毫秒），可追溯回原始对话
     */
    record Triple(String subject, String predicate, String object,
                  long validFrom, Long validTo, long sourceTs) {
    }
}
