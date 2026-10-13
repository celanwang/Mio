package org.celanwang.mio.tool;

import org.celanwang.mio.service.TencentRideService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class TencentRideTool extends ToolBase {
    public static final String NAME = "tencent_ride";
    public static final List<String> COMMANDS = List.of("preflight", "get-resident-city", "set-resident-city",
            "delete-state", "addr-keys", "addr-get-value", "addr-upsert-value", "addr-sync-to-state",
            "short-cut-keys", "short-cut-get-value", "short-cut-upsert-value", "poi-search", "select-poi",
            "estimate-price", "query-ongoing-order", "query-order", "check-cancel-fee",
            "cancel-order", "query-driver-location", "build-static-map-url");
    private static final Set<String> CONFIRM = Set.of("cancel-order", "delete-state",
            "set-resident-city", "addr-upsert-value", "short-cut-upsert-value");
    private final TencentRideService service;

    public TencentRideTool(TencentRideService service) {
        super(ToolBase.builder().name(NAME)
                .description("腾讯出行打车查询和行程工具：先读取 tencent-ride 技能及 references/project-integration.md。command 选择固定操作，parameters 是该命令参数对象，不接受 shell、文件路径或 Token。首次使用 preflight，常住城市可不设置；主动询问本次城市、上车点和目的地，poi-search 必须携带已确认的 city_name。地点确定后询价并展示真实里程、时长、车型和金额；只询价不下单。创建订单必须使用独立工具 tencent_ride_create_order；本工具不接受 create-order 命令。query-order 可查询真实订单状态、司机姓名和车牌，未分配司机时如实说明；query-driver-location 查询司机位置。cancel-order 须先 check-cancel-fee，再带真实 orderId 与 amountFen，summary 填写订单和取消费用。取消和保存偏好由用户确认。不提供付款二维码，支付按真实订单状态到腾讯出行服务小程序处理。")
                .inputSchema(Map.of("type", "object", "properties", Map.of(
                        "command", Map.of("type", "string", "enum", COMMANDS),
                        "parameters", Map.of("type", "object", "description", "preflight 和 estimate-price 使用空对象 {}。poi-search 使用 keyword、city_name、scene（1 上车、2 下车）；select-poi 使用 poiid（不是 poi_id）、scene、最近搜索返回的 candidate_id，多个推荐点不能只靠 poiid 选择。先核对站名、区县、线路及出口，再搜索并选择两端；实际 point_name 可能与 POI name 不同，偏移须向用户确认。地点纠正后重新搜索、选择、询价。estimate-price 从草稿读取起终点，不接受地址、坐标或 POI 参数；结果 trip 是本次实际路线，回复不可改名，单位见 units。query-order 和 query-ongoing-order 仅支持当前进行中的订单，不支持历史订单。其他命令参见 references/project-integration.md，使用参数名，不含 --。"),
                        "stateVersion", Map.of("type", "string", "description", "询价工具返回的报价状态版本，下单必填。"),
                        "summary", Map.of("type", "string", "description", "下单的起终点、车型、报价，或取消的订单与费用。")),
                        "required", List.of("command"), "additionalProperties", false))
                .readOnly(false).concurrencySafe(false));
        this.service = service;
    }

    public static boolean isSupported(Object command) { return "create-order".equals(command) || COMMANDS.contains(command); }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
        Object command = input.get("command");
        if ("create-order".equals(command)) return Mono.just(PermissionDecision.deny("下单请使用 tencent_ride_create_order，直接传 price_estimate_keys、stateVersion 和 summary。"));
        if (!COMMANDS.contains(command)) return Mono.just(PermissionDecision.deny("不支持的打车命令。"));
        if (CONFIRM.contains(command)) {
            return Mono.just(PermissionDecision.ask("请确认本次打车操作；批准仅适用于这一次调用。"));
        }
        return Mono.just(PermissionDecision.allow("查询或编辑本次行程草稿，不创建或取消订单。"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
        return Mono.fromCallable(() -> {
            if ("create-order".equals(call.getInput().get("command"))) {
                return ToolResultBlock.text("下单必须使用 tencent_ride_create_order。当前调用没有创建订单。");
            }
            String session = call.getRuntimeContext() == null ? null : call.getRuntimeContext().getSessionId();
            return ToolResultBlock.text(service.execute(session, call.getInput()));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
