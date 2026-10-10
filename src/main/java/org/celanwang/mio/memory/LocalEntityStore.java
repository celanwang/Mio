package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link EntityStore} 的本地实现：三元组追加写 .mio/entities.jsonl，
 * 内存按 subject 建索引。best-effort：写失败只 warn，读损坏单行跳过。
 */
@Component
public class LocalEntityStore implements EntityStore {

    private static final Logger log = LoggerFactory.getLogger(LocalEntityStore.class);

    private final Path file;
    private final ObjectMapper objectMapper;
    private final Map<String, List<Triple>> bySubject = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    public LocalEntityStore(@Value("${app.memory.dir:./.mio}") String dir, ObjectMapper objectMapper) {
        this.file = JsonFileSupport.expandHome(dir).resolve("entities.jsonl");
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized void append(List<Triple> triples) {
        ensureLoaded();
        for (Triple triple : triples) {
            if (triple == null || !StringUtils.hasText(triple.subject())
                    || !StringUtils.hasText(triple.predicate()) || !StringUtils.hasText(triple.object())) {
                continue;
            }
            boolean exists = bySubject.getOrDefault(triple.subject(), List.of()).stream()
                    .anyMatch(t -> t.validTo() == null
                            && t.predicate().equals(triple.predicate())
                            && t.object().equals(triple.object()));
            if (exists) {
                continue;
            }
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, objectMapper.writeValueAsString(triple) + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                bySubject.computeIfAbsent(triple.subject(), k -> new ArrayList<>()).add(triple);
            } catch (Exception e) {
                log.warn("实体三元组写入失败（fail-open，跳过）：{}", e.getMessage());
            }
        }
    }

    @Override
    public List<Triple> related(String entity) {
        ensureLoaded();
        return bySubject.getOrDefault(entity, List.of()).stream()
                .filter(t -> t.validTo() == null).toList();
    }

    @Override
    public List<Triple> all() {
        ensureLoaded();
        return bySubject.values().stream().flatMap(List::stream)
                .filter(t -> t.validTo() == null).toList();
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            loaded = true;
            if (!Files.exists(file)) {
                return;
            }
            try (BufferedReader reader = Files.newBufferedReader(file)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        Triple triple = objectMapper.readValue(line, Triple.class);
                        bySubject.computeIfAbsent(triple.subject(), k -> new ArrayList<>()).add(triple);
                    } catch (Exception e) {
                        // 单行损坏跳过，不影响整体
                    }
                }
            } catch (Exception e) {
                log.warn("实体簿加载失败（fail-open，按空处理）：{}", e.getMessage());
            }
        }
    }
}
