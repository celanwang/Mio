package org.celanwang.mio.memory;

/**
 * 一条自动化规则：由习惯检测生成提议，必须经用户显式批准才会生效。
 *
 * @param id        规则 ID
 * @param title     规则标题（做什么，如「自动帮你领取可用优惠券」）
 * @param toolName  关联工具名（白名单内）
 * @param trigger   触发方式：本轮只支持 session-start（会话开始时由 Agent 执行）
 * @param status    状态：PROPOSED 待决定 / ACTIVE 已批准 / REJECTED 已拒绝（不再重复提议）
 * @param reason    生成提议的依据说明
 * @param createdAt 提议生成时间（毫秒）
 * @param decidedAt 用户决定时间（毫秒）；PROPOSED 状态为 null
 */
public record AutomationRule(String id, String title, String toolName, String trigger,
                             Status status, String reason, long createdAt, Long decidedAt) {

    public enum Status {
        PROPOSED, ACTIVE, REJECTED
    }
}
