package org.celanwang.mio.security;

/** 一次裁决的结果：effect 三态 + 规则来源 + 理由，随 policy_decision 事件留痕。 */
public record PolicyDecision(PolicyEffect effect, String source, String reason) {
}
