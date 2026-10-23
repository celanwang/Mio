package org.celanwang.mio.memory;

import java.util.List;

/**
 * episode 原文存储接缝：会话消息原始记录（画像可重建的源）的追加与读取。
 * 唯一实现为 PG（{@link PgEpisodeStore}），与向量同库，保证向量引用的 episode 原文一定可取到。
 * 实现必须 best-effort：写失败返回 FAILED，调用方保留补记机会，绝不阻断对话。
 */
public interface EpisodeStore {

    enum AppendResult { INSERTED, EXISTING, FAILED }

    /** 追加一条原文；只有数据库确认后才能用于去重或建立派生索引。 */
    AppendResult append(Episode episode);

    /** 仅供离线诊断；运行时召回和历史补建不使用全量读取。 */
    List<Episode> listAll();

    /** Bounded primary-source lookup after vector search; missing rows are never fabricated. */
    List<Episode> findByIds(List<String> ids);

    /** Strict source lookup for durable workers. */
    java.util.Optional<Episode> find(String id);

    /**
     * 一条会话消息原始记录。
     *
     * @param id 新消息使用会话与 SDK 消息身份的 SHA-256；历史指纹仍可读取，PG 按 id 幂等
     * @param sessionId 来源会话标识，可空
     */
    record Episode(String id, String sessionId, long timestamp, String role, String text) {
    }
}
