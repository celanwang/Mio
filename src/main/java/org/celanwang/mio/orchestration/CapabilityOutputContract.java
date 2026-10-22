package org.celanwang.mio.orchestration;

import java.util.*;

/** Trusted capability configuration describes evidence; the planner can select but cannot redefine it. */
public record CapabilityOutputContract(String id,String description,Map<String,String> requiredFacts,TaskScope requiredScope) {
    public CapabilityOutputContract {
        if(id==null||!id.matches("[a-z][a-z0-9-]{0,63}")||description==null||description.isBlank()||description.length()>1000)
            throw new IllegalArgumentException("输出契约编号或说明无效");
        requiredFacts=Map.copyOf(requiredFacts);
        if(requiredFacts.size()>8||requiredFacts.entrySet().stream().anyMatch(e->!e.getKey().matches("[a-z][a-z0-9_.]{0,79}")||!e.getValue().matches("[a-z][a-z0-9_]{0,63}")))
            throw new IllegalArgumentException("输出事实契约无效");
        if(id.equals("report")&&!requiredFacts.isEmpty()||!id.equals("report")&&requiredFacts.isEmpty())
            throw new IllegalArgumentException("仅报告契约可以不要求业务证据");
    }
    public void validate(TaskSpec spec) {
        if(requiredScope!=null&&spec.scope()!=requiredScope)throw new IllegalArgumentException("输出契约不适用于当前任务范围");
    }
    public String version(){return JsonFingerprint.hash(this);}
    public static CapabilityOutputContract report(){return new CapabilityOutputContract("report","工作报告；不证明用户目标或业务履约完成",Map.of(),null);}
    public static CapabilityOutputContract rideQuote(){return new CapabilityOutputContract("ride-quote","取得仍有效的可信行程报价；不证明订单已创建",Map.of("ride.quote","ride_quote"),TaskScope.PREPARE);}
    public static CapabilityOutputContract resourceReference(){return new CapabilityOutputContract("resource-reference","操作已由关联回执核实并取得资源编号；不证明后续支付或履约",Map.of("operation.business_id","provider_resource_reference"),TaskScope.REQUEST_APPROVAL);}
}
