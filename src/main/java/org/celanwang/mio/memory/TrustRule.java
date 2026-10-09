package org.celanwang.mio.memory;

/**
 * 一条信任规则统计（本轮只统计，不做自动放行）。
 *
 * @param toolName       工具名
 * @param paramSignature 参数骨架（排序后的参数 key 列表，不含值），如 create-order[addressId,items,storeId]
 * @param approvals      人工批准次数
 * @param rejections     人工拒绝次数
 * @param lastDecisionAt 最近一次人工决定时间（毫秒）
 */
public record TrustRule(String toolName, String paramSignature,
                        int approvals, int rejections, long lastDecisionAt) {
}
