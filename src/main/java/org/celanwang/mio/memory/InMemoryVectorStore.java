package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link VectorStore} 的 Lv0 实现：向量持久化在 memory/episodes-vectors.jsonl，
 * 启动时流式读入内存，检索为暴力余弦（万级规模 <10ms）。
 * 容量护栏：超过 {@link #CAPACITY_WARN_AT} 条时 warn 提示升级 Lucene HNSW。
 */
@Component
public class InMemoryVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStore.class);

    private static final int CAPACITY_WARN_AT = 50_000;

    private final Path file;
    private final ObjectMapper objectMapper;
    private final List<String> ids = new ArrayList<>();
    private final List<float[]> vectors = new ArrayList<>();
    private final Map<String, Integer> indexById = new HashMap<>();
    private volatile boolean loaded = false;

    public InMemoryVectorStore(@Value("${app.memory.dir:./.mio}") String dir, ObjectMapper objectMapper) {
        this.file = JsonFileSupport.expandHome(dir).resolve("memory/episodes-vectors.jsonl");
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized void upsert(String id, float[] vector) {
        ensureLoaded();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, objectMapper.writeValueAsString(new VectorLine(id, vector)) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("向量落盘失败（fail-open，仅内存生效）：{}", e.getMessage());
        }
        Integer existing = indexById.get(id);
        if (existing != null) {
            vectors.set(existing, vector);
        } else {
            indexById.put(id, vectors.size());
            ids.add(id);
            vectors.add(vector);
        }
        if (vectors.size() == CAPACITY_WARN_AT) {
            log.warn("向量数已达 {} 条，接近内存暴力检索的舒适上限，建议规划升级 Lucene HNSW",
                    CAPACITY_WARN_AT);
        }
    }

    @Override
    public List<ScoredId> search(float[] query, int topK) {
        ensureLoaded();
        double queryNorm = norm(query);
        if (queryNorm == 0) {
            return List.of();
        }
        List<ScoredId> scored = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            double score = cosine(query, vectors.get(i), queryNorm);
            scored.add(new ScoredId(ids.get(i), score));
        }
        scored.sort(Comparator.comparingDouble(ScoredId::score).reversed());
        return scored.size() <= topK ? scored : scored.subList(0, topK);
    }

    @Override
    public boolean contains(String id) {
        ensureLoaded();
        return indexById.containsKey(id);
    }

    @Override
    public int size() {
        ensureLoaded();
        return ids.size();
    }

    private double cosine(float[] a, float[] b, double normA) {
        double normB = norm(b);
        if (normB == 0) {
            return 0;
        }
        double dot = 0;
        int length = Math.min(a.length, b.length);
        for (int i = 0; i < length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot / (normA * normB);
    }

    private double norm(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        return Math.sqrt(sum);
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
                        VectorLine parsed = objectMapper.readValue(line, VectorLine.class);
                        if (!indexById.containsKey(parsed.id()) && parsed.vector() != null) {
                            indexById.put(parsed.id(), vectors.size());
                            ids.add(parsed.id());
                            vectors.add(parsed.vector());
                        }
                    } catch (Exception e) {
                        // 单行损坏跳过，不影响整体
                    }
                }
                log.info("向量索引加载完成：{} 条", ids.size());
            } catch (Exception e) {
                log.warn("向量文件加载失败（fail-open，按空索引处理）：{}", e.getMessage());
            }
        }
    }

    /** episodes-vectors.jsonl 的单行结构。 */
    record VectorLine(String id, float[] vector) {
    }
}
