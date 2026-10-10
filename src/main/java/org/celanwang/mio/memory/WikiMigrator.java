package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 启动迁移器：把旧的扁平画像（profile.json 条目列表）一次性迁移为 Wiki 页。
 * 条件：wiki/ 为空且 profile.json 存在；迁移完成后原名保留为 profile.json.migrated。
 * fail-open：迁移失败只记日志、不阻断启动，下轮启动重试。
 */
@Component
public class WikiMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WikiMigrator.class);

    private final Path memoryDir;
    private final WikiStore wikiStore;
    private final ObjectMapper objectMapper;

    public WikiMigrator(@Value("${app.memory.dir:./.mio}") String dir,
                        WikiStore wikiStore, ObjectMapper objectMapper) {
        this.memoryDir = JsonFileSupport.expandHome(dir);
        this.wikiStore = wikiStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path legacy = memoryDir.resolve("profile.json");
        if (!Files.exists(legacy) || !wikiStore.isEmpty()) {
            return;
        }
        try {
            LegacyProfile parsed = objectMapper.readValue(Files.readString(legacy), LegacyProfile.class);
            if (parsed.entries() == null) {
                return;
            }
            int migrated = 0;
            for (LegacyEntry entry : parsed.entries()) {
                if (entry == null || entry.validTo() != null
                        || entry.key() == null || entry.value() == null) {
                    continue;
                }
                String scope = entry.scope() == null ? WikiStore.SCOPE_GLOBAL : entry.scope();
                String source = entry.source() == null ? WikiStore.SOURCE_INFERRED : entry.source();
                wikiStore.savePage(new WikiPage(WikiStore.sanitize(entry.key()), entry.key(), scope,
                        source, entry.confidence(), entry.updatedAt(), entry.value()));
                migrated++;
            }
            Files.move(legacy, legacy.resolveSibling("profile.json.migrated"),
                    StandardCopyOption.REPLACE_EXISTING);
            log.info("画像迁移完成：{} 条条目 → wiki 页面，profile.json 已改名 .migrated 保留", migrated);
        } catch (Exception e) {
            log.warn("画像迁移失败（fail-open，下次启动重试）：{}", e.getMessage());
        }
    }

    /** 旧版 profile.json 的文件结构（仅为迁移保留）。 */
    record LegacyProfile(List<LegacyEntry> entries) {
    }

    record LegacyEntry(String key, String value, String scope, String source,
                       double confidence, long updatedAt, Long validTo) {
    }
}
