package org.celanwang.mio.orchestration;

import java.util.*;

/** Trusted capability configuration describes evidence; the planner can select but cannot redefine it. */
public record CapabilityOutputContract(String id,String description,Map<String,String> requiredFacts,TaskScope requiredScope,List<Field> fields) {
    public enum ValueType { NUMBER,TEXT }
    public record Field(String id,String label,String factKey,String valueKey,ValueType type,String unit) {
        public Field {
            if(id==null||!id.matches("[a-z][a-zA-Z0-9_]{0,63}")||label==null||label.isBlank()||label.length()>100
                    ||factKey==null||valueKey==null||!valueKey.matches("[a-z][a-zA-Z0-9_]{0,63}")||type==null||unit==null||unit.length()>30)
                throw new IllegalArgumentException("验收字段定义无效");
        }
    }
    public CapabilityOutputContract(String id,String description,Map<String,String> requiredFacts,TaskScope requiredScope) {
        this(id,description,requiredFacts,requiredScope,List.of());
    }
    public CapabilityOutputContract {
        if(id==null||!id.matches("[a-z][a-z0-9-]{0,63}")||description==null||description.isBlank()||description.length()>1000)
            throw new IllegalArgumentException("输出契约编号或说明无效");
        requiredFacts=Map.copyOf(requiredFacts);
        fields=fields==null?List.of():List.copyOf(fields);
        if(requiredFacts.size()>8||requiredFacts.entrySet().stream().anyMatch(e->!e.getKey().matches("[a-z][a-z0-9_.]{0,79}")||!e.getValue().matches("[a-z][a-z0-9_]{0,63}")))
            throw new IllegalArgumentException("输出事实契约无效");
        if(id.equals("report")&&!requiredFacts.isEmpty()||!id.equals("report")&&requiredFacts.isEmpty())
            throw new IllegalArgumentException("仅报告契约可以不要求业务证据");
        if(fields.size()>8||fields.stream().map(Field::id).distinct().count()!=fields.size())throw new IllegalArgumentException("验收字段过多或重复");
        for(var field:fields)if(!requiredFacts.containsKey(field.factKey()))throw new IllegalArgumentException("验收字段必须来自本契约要求的事实");
    }
    public void validate(TaskSpec spec) {
        if(requiredScope!=null&&spec.scope()!=requiredScope)throw new IllegalArgumentException("输出契约不适用于当前任务范围");
    }
    public String version(){return JsonFingerprint.hash(this);}
    public static CapabilityOutputContract report(){return new CapabilityOutputContract("report","工作报告；不证明用户目标或业务履约完成",Map.of(),null);}
    public static CapabilityOutputContract rideQuote(){return new CapabilityOutputContract("ride-quote","取得仍有效的可信行程报价；不证明订单已创建",Map.of("ride.quote","ride_quote"),TaskScope.PREPARE,
            List.of(new Field("durationSeconds","预计行程耗时","ride.quote","durationSeconds",ValueType.NUMBER,"秒"),
                    new Field("distanceMeters","行程距离","ride.quote","distanceMeters",ValueType.NUMBER,"米")));}
    public static CapabilityOutputContract resourceReference(){return new CapabilityOutputContract("resource-reference","操作已由关联回执核实并取得资源编号；不证明后续支付或履约",Map.of("operation.business_id","provider_resource_reference"),TaskScope.REQUEST_APPROVAL,
            List.of(new Field("businessId","资源编号","operation.business_id","businessId",ValueType.TEXT,"")));}
}
