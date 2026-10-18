package org.celanwang.mio.config;

import java.util.Set;
import java.util.Map;

/**
 * 工具权限分层：只读工具自动放行；写操作按可逆性分轨——不可逆操作（支付/下单/对外发布）
 * 走硬人工轨，决定权永远在人；可逆写操作走自动审核轨，先由 Jev 审核，不通过转人工；
 * 未列入的工具按默认策略转人工确认，并在确认卡片上标记首见。
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
            "query-party-city",
            // 微博本机准备步骤：只检查授权并建立连接，不执行业务操作。
            "prepare_weibo");

    /** 不可逆写操作（支付/下单）：硬人工轨，永远人工确认，不走 Jev 自动审核。 */
    public static final Set<String> HUMAN_ONLY = Set.of(
            "create-order", "mall-create-order", "party-order-create",
            "tencent_ride_create_order");

    /** 可逆写操作：自动审核轨，先由 Jev 审核，通过自动执行，不通过转人工确认。 */
    public static final Set<String> JEV_REVIEWABLE = Set.of(
            "cancel-order", "auto-bind-coupons",
            "delivery-create-address", "draw-lottery",
            // 腾讯打车：询价/查询由工具自身放行，需确认的命令进入审核。
            "tencent_ride");

    /** 允许习惯检测生成自动化提议的白名单工具（非白名单工具永不提议）。 */
    public static final Set<String> AUTOMATABLE = Set.of(
            "auto-bind-coupons", "draw-lottery", "query-my-coupons", "query-promotions");

    /** 打车取消有独立的费用确认，不能被通用工具名的 Jev 自动审核覆盖。 */
    public static boolean isJevReviewable(String toolName, Map<String, Object> input) {
        return JEV_REVIEWABLE.contains(toolName)
                && !("tencent_ride".equals(toolName) && "cancel-order".equals(input.get("command")));
    }

    private ToolPolicies() {
    }
}
