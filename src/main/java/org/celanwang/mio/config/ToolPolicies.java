package org.celanwang.mio.config;

import java.util.Set;

/**
 * 工具权限分层：只读工具自动放行，模型自主执行；写操作进入审核（Jev 通过则自动执行，
 * 不通过转人工确认）；未列入的工具按默认策略转人工确认。
 */
public final class ToolPolicies {

    /** 只读查询与算价，来自麦当劳 MCP 实际注册的工具清单。 */
    public static final Set<String> READ_ONLY = Set.of(
            "now-time-info",
            "delivery-query-addresses", "delivery-query-stores", "query-nearby-stores",
            "query-meals", "query-meal-detail", "query-store-coupons",
            "query-my-coupons", "available-coupons", "query-my-account", "query-my-prizes",
            "query-promotions", "query-lottery-info", "query-survey-coupon",
            "query-meal-assistance", "list-nutrition-foods",
            "calculate-price", "query-order", "order-list",
            "mall-points-products", "mall-product-detail", "mall-order-list", "mall-order-detail",
            "campaign-calendar",
            "query-party-store", "query-party-store-date", "query-party-store-session",
            "query-party-city");

    /** 写操作：先由 Jev 审核，通过自动执行，不通过转人工确认。 */
    public static final Set<String> GUARDED = Set.of(
            "create-order", "cancel-order", "auto-bind-coupons",
            "delivery-create-address", "mall-create-order", "party-order-create",
            "draw-lottery");

    private ToolPolicies() {
    }
}
