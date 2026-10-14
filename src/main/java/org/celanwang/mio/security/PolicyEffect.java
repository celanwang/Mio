package org.celanwang.mio.security;

/** 裁决结果三态：放行、询问、拒绝。求值优先级固定为 DENY > ASK > ALLOW。 */
public enum PolicyEffect {
    ALLOW,
    ASK,
    DENY
}
