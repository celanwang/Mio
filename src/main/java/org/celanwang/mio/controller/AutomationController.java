package org.celanwang.mio.controller;

import org.celanwang.mio.memory.AutomationRule;
import org.celanwang.mio.memory.AutomationStore;
import org.celanwang.mio.trace.TraceStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 自动化规则 API：习惯提议的批准/拒绝/吊销只能走这里（或面板），保证 HITL。
 * 批准/拒绝动作写事件日志，保持审计链一致。
 */
@RestController
@RequestMapping("/api/automations")
public class AutomationController {

    /** 审计事件挂在固定伪会话下，便于在面板中集中查看。 */
    private static final String AUDIT_SESSION = "automations";

    private final AutomationStore automationStore;
    private final TraceStore traceStore;

    public AutomationController(AutomationStore automationStore, TraceStore traceStore) {
        this.automationStore = automationStore;
        this.traceStore = traceStore;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String,String>> unavailable(){return org.springframework.http.ResponseEntity.status(503).body(java.util.Map.of("message","自动化状态或审计暂不可用，未完成变更"));}

    @GetMapping
    public List<AutomationRule> rules() {
        return automationStore.rules();
    }

    @PostMapping("/{id}/approve")
    public List<AutomationRule> approve(@PathVariable String id) {
        AutomationRule rule = automationStore.approve(id);
        if (rule == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "规则不存在或状态已改变，请刷新。");
        }
        return automationStore.rules();
    }

    @PostMapping("/{id}/reject")
    public List<AutomationRule> reject(@PathVariable String id) {
        AutomationRule rule = automationStore.reject(id);
        if (rule == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "规则不存在或状态已改变，请刷新。");
        }
        return automationStore.rules();
    }

    /** 吊销 ACTIVE 规则（置为 REJECTED，保留记录防止立即重复提议）。 */
    @DeleteMapping("/{id}")
    public List<AutomationRule> revoke(@PathVariable String id) {
        AutomationRule rule = automationStore.revoke(id);
        if (rule == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在或未处于已批准状态。");
        }
        return automationStore.rules();
    }
}
