package org.celanwang.mio.memory;

import java.util.List;

/**
 * episode 原文存储接缝：会话消息原始记录（画像可重建的源）的追加与读取。
 * 本地实现为 JSONL 落盘（{@link LocalEpisodeStore}，默认）；PG 实现（{@link PgEpisodeStore}）
 * 与向量同库，保证向量引用的 episode 原文一定可取到。
 * 实现必须 best-effort：写失败只 warn，绝不阻断对话。
 */
public interface EpisodeStore {

    /** 追加一条 episode 原文。 */
    void append(Episode episode);

    /** 全量 episode（启动回填向量索引用），按时间升序。 */
    List<Episode> listAll();

    /**
     * 一条会话消息原始记录。
     *
     * @param id 稳定指纹（见 {@link EpisodeRetriever#episodeId}），重启后由原文重算可对齐，PG 实现按它幂等
     * @param sessionId 来源会话标识，可空；不参与 id 指纹计算
     */
    record Episode(String id, String sessionId, long timestamp, String role, String text) {
    }
}
