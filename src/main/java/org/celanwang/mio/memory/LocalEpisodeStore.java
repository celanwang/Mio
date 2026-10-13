package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link EpisodeStore} 的本地实现（默认）：episode 追加写 memory/episodes.jsonl。
 * best-effort：写失败只 warn，读损坏单行跳过。id 不落盘，读取时由原文重算。
 */
@Component
@ConditionalOnProperty(name = "app.memory.store", havingValue = "local", matchIfMissing = true)
public class LocalEpisodeStore implements EpisodeStore {

    private static final Logger log = LoggerFactory.getLogger(LocalEpisodeStore.class);

    private final Path file;
    private final ObjectMapper objectMapper;

    public LocalEpisodeStore(@Value("${app.memory.dir:./.mio}") String dir, ObjectMapper objectMapper) {
        this.file = JsonFileSupport.expandHome(dir).resolve("memory/episodes.jsonl");
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized void append(Episode episode) {
        try {
            Files.createDirectories(file.getParent());
            String line = objectMapper.writeValueAsString(
                    new EpisodeLine(episode.timestamp(), episode.sessionId(), episode.role(), episode.text()));
            Files.writeString(file, line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("会话消息落盘失败（不影响主流程）：{}", e.getMessage());
        }
    }

    @Override
    public List<Episode> listAll() {
        List<Episode> episodes = new ArrayList<>();
        if (!Files.exists(file)) {
            return episodes;
        }
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    EpisodeLine parsed = objectMapper.readValue(line, EpisodeLine.class);
                    episodes.add(new Episode(
                            EpisodeRetriever.episodeId(parsed.role(), parsed.timestamp(), parsed.text()),
                            parsed.sessionId(), parsed.timestamp(), parsed.role(), parsed.text()));
                } catch (Exception e) {
                    // 单行损坏跳过，不影响整体
                }
            }
        } catch (Exception e) {
            log.warn("episodes 加载失败（fail-open，按空处理）：{}", e.getMessage());
        }
        return episodes;
    }

    /** episodes.jsonl 的单行结构（与历史文件格式一致，id 不落盘、由原文重算；旧行无 sessionId 字段，读取时为 null）。 */
    private record EpisodeLine(long timestamp, String sessionId, String role, String text) {
    }
}
