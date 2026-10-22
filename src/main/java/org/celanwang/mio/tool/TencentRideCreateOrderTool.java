package org.celanwang.mio.tool;

import org.celanwang.mio.service.TencentRideService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 下单使用独立的扁平参数，避免把接口字段或确认摘要塞进通用 parameters。 */
public class TencentRideCreateOrderTool extends ToolBase implements org.celanwang.mio.orchestration.OperationBoundTool {
    public static final String NAME = "tencent_ride_create_order";
    private static final Set<String> FIELDS = Set.of("price_estimate_keys", "user_prefer_labels", "stateVersion", "summary");
    private final TencentRideService service;

    public TencentRideCreateOrderTool(TencentRideService service) {
        super(ToolBase.builder().name(NAME)
                .description("创建腾讯即时打车订单，必须先用 tencent_ride 询价并取得本次真实报价。直接传 price_estimate_keys、stateVersion、summary，不能再套 command 或 parameters，也不传地址、坐标、车型名、Token 或预约时间。选择报价中的真实 priceEstimateKey，stateVersion 原样复制询价结果，summary 清楚写明起终点、所选平台及车型、预估金额和偏好。用户要求最便宜时选最低价，同价无平台偏好可选接口返回的第一项并在摘要中明示。参数有效后展示允许/拒绝确认，批准仅用于本次下单。失败后先查询当前订单，不自动重试。下单成功且用户要司机姓名和车牌时用 tencent_ride 的 query-order 查询；派单中说明尚未分配，不要求先支付才查询。")
                .inputSchema(Map.of("type", "object", "properties", Map.of(
                        "price_estimate_keys", Map.of("type", "array", "minItems", 1, "uniqueItems", true,
                                "items", Map.of("type", "string", "minLength", 1),
                                "description", "所选报价中的真实 priceEstimateKey 数组，不能用平台名、车型名或 estimateKey 代替。"),
                        "user_prefer_labels", Map.of("type", "array", "uniqueItems", true,
                                "items", Map.of("type", "integer", "enum", List.of(1, 2, 3, 4)),
                                "description", "可选乘客偏好：1 接驾近，2 接驾快，3 电车，4 油车；无偏好可省略。"),
                        "stateVersion", Map.of("type", "string", "minLength", 1, "description", "原样复制本次 estimate-price 返回的顶层 stateVersion。"),
                        "summary", Map.of("type", "string", "minLength", 1, "description", "本次确认摘要：起终点、具体平台与车型、预估金额及偏好。")),
                        "required", List.of("price_estimate_keys", "stateVersion", "summary"), "additionalProperties", false))
                .readOnly(false).concurrencySafe(false));
        this.service = service;
    }

    private static String validationError(Map<String, Object> input) {
        if (!FIELDS.containsAll(input.keySet())) {
            return "下单字段必须直接位于顶层：price_estimate_keys、stateVersion、summary，可选 user_prefer_labels；不要传 command、parameters 或接口原始字段。";
        }
        if (!(input.get("price_estimate_keys") instanceof List<?> keys) || keys.isEmpty()
                || keys.stream().anyMatch(key -> !(key instanceof String text) || text.isBlank())
                || keys.stream().distinct().count() != keys.size()) {
            return "price_estimate_keys 必须是本次报价中真实 priceEstimateKey 的非空字符串数组。";
        }
        for (String field : List.of("stateVersion", "summary")) {
            if (!(input.get(field) instanceof String text) || text.isBlank()) return "下单缺少 " + field + "。";
        }
        if (input.containsKey("user_prefer_labels")) {
            if (!(input.get("user_prefer_labels") instanceof List<?> labels)
                    || labels.stream().anyMatch(label -> !(label instanceof Number number)
                        || !List.of(1d, 2d, 3d, 4d).contains(number.doubleValue()))
                    || labels.stream().map(value -> ((Number) value).intValue()).distinct().count() != labels.size()) {
                return "user_prefer_labels 必须是 1、2、3、4 中的整数数组；无偏好可省略。";
            }
        }
        return null;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
        String error = validationError(input);
        return Mono.just(error == null ? PermissionDecision.ask("请核对本次起终点、车型和预估金额后确认叫车。")
                : PermissionDecision.deny(error));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
        return execute(call,null,null);
    }
    @Override public String operationScope(){return service.operationScope();}
    @Override public Mono<ToolResultBlock> callWithOperation(ToolCallParam call,String logicalId,String scopeKey){return execute(call,logicalId,scopeKey);}
    private Mono<ToolResultBlock> execute(ToolCallParam call,String logicalId,String scopeKey){
        return Mono.fromCallable(() -> {
            String error = validationError(call.getInput());
            if (error != null) return ToolResultBlock.text(error);
            var input = call.getInput();
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("price_estimate_keys", input.get("price_estimate_keys"));
            if (input.get("user_prefer_labels") instanceof List<?> labels) {
                parameters.put("user_prefer_labels", labels.stream().map(value -> ((Number) value).intValue()).toList());
            }
            Map<String, Object> request = new LinkedHashMap<>(Map.of("command", "create-order", "parameters", parameters,
                    "stateVersion", input.get("stateVersion"), "summary", input.get("summary")));
            if(logicalId!=null){
                if(!service.operationScope().equals(scopeKey))throw new IllegalStateException("账号配置已改变，请重新准备操作");
                request.put("logicalOperationId",logicalId);request.put("operationScope",scopeKey);
            }
            String session = call.getRuntimeContext() == null ? null : call.getRuntimeContext().getSessionId();
            return ToolResultBlock.text(service.execute(session, request));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
