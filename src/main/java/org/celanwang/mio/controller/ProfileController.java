package org.celanwang.mio.controller;

import org.celanwang.mio.memory.ProfileEntry;
import org.celanwang.mio.memory.ProfileStore;
import org.celanwang.mio.memory.TrustRule;
import org.celanwang.mio.memory.TrustStore;
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
 * 用户画像与信任统计 API：画像可见/可编辑/可删除，信任统计本轮只观察不放行。
 */
@RestController
@RequestMapping("/api")
public class ProfileController {

    private final ProfileStore profileStore;
    private final TrustStore trustStore;

    public ProfileController(ProfileStore profileStore, TrustStore trustStore) {
        this.profileStore = profileStore;
        this.trustStore = trustStore;
    }

    @GetMapping("/profile")
    public List<ProfileEntry> profile() {
        return profileStore.entries();
    }

    /** 手动新增/修改条目：来源记为 explicit，置信度 1.0。 */
    @PutMapping("/profile")
    public List<ProfileEntry> upsert(@RequestBody ProfileRequest request) {
        if (request == null || !StringUtils.hasText(request.key()) || !StringUtils.hasText(request.value())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "key 和 value 不能为空。");
        }
        String scope = StringUtils.hasText(request.scope())
                ? request.scope().trim() : ProfileStore.SCOPE_GLOBAL;
        profileStore.upsert(new ProfileEntry(request.key().trim(), request.value().trim(), scope,
                ProfileStore.SOURCE_EXPLICIT, 1.0, System.currentTimeMillis(), null));
        return profileStore.entries();
    }

    @DeleteMapping("/profile")
    public List<ProfileEntry> remove(@RequestParam String scope, @RequestParam String key) {
        profileStore.remove(key, scope);
        return profileStore.entries();
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

    public record ProfileRequest(String key, String value, String scope) {
    }
}
