package org.celanwang.mio.controller;

import org.celanwang.mio.memory.EntityStore;
import org.celanwang.mio.memory.TrustRule;
import org.celanwang.mio.memory.TrustStore;
import org.celanwang.mio.memory.WikiPage;
import org.celanwang.mio.memory.WikiStore;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 用户画像（Wiki 页）与信任统计 API：画像可见/可编辑/可删除，信任统计本轮只观察不放行。
 */
@RestController
@RequestMapping("/api")
public class ProfileController {

    private final WikiStore wikiStore;
    private final EntityStore entityStore;
    private final TrustStore trustStore;

    public ProfileController(WikiStore wikiStore, EntityStore entityStore, TrustStore trustStore) {
        this.wikiStore = wikiStore;
        this.entityStore = entityStore;
        this.trustStore = trustStore;
    }

    @GetMapping("/profile")
    public List<WikiPage> profile() {
        return wikiStore.pages();
    }

    /** 手动新增/编辑页面：来源记为 explicit，置信度 1.0；带 slug 为整页更新，否则新建。 */
    @PutMapping("/profile")
    public List<WikiPage> upsert(@RequestBody ProfileRequest request) {
        if (request == null || !StringUtils.hasText(request.title()) || !StringUtils.hasText(request.content())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title 和 content 不能为空。");
        }
        String scope = StringUtils.hasText(request.scope())
                ? request.scope().trim() : WikiStore.SCOPE_GLOBAL;
        String slug = StringUtils.hasText(request.slug())
                ? request.slug().trim() : request.title().trim();
        wikiStore.savePage(new WikiPage(WikiStore.sanitize(slug), request.title().trim(), scope,
                WikiStore.SOURCE_EXPLICIT, 1.0, System.currentTimeMillis(), request.content().trim()));
        return wikiStore.pages();
    }

    @DeleteMapping("/profile")
    public List<WikiPage> remove(@RequestParam String slug) {
        wikiStore.deletePage(slug);
        return wikiStore.pages();
    }

    /** 实体簿只读观察接口（消费方在后续阶段接入）。 */
    @GetMapping("/entities")
    public List<EntityStore.Triple> entities() {
        return entityStore.all();
    }

    @GetMapping("/trust")
    public List<TrustRule> trust() {
        return trustStore.rules();
    }

    @DeleteMapping("/trust")
    public List<TrustRule> resetTrust(@RequestParam String tool) {
        trustStore.reset(tool);
        return trustStore.rules();
    }

    public record ProfileRequest(String slug, String title, String scope, String content) {
    }
}
