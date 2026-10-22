package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.service.TencentRideService;
import reactor.core.publisher.Mono;
import java.util.Map;

public final class TencentOperationReconciler implements OperationReconciler {
    private final TencentRideService service;private final ObjectMapper json=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public TencentOperationReconciler(TencentRideService service){this.service=service;}
    public boolean supports(OperationRecord op){return "tencent_ride_create_order".equals(op.tool())&&!op.scopeKey().isEmpty()&&op.logicalId().matches("[a-f0-9-]{36}");}
    public Mono<OperationReceipt> reconcile(TaskRecord task,OperationRecord op){
        return Mono.fromCallable(()->{
            String raw=service.reconcile(task.childSessionId(),op.logicalId(),op.scopeKey());
            if(raw.length()>128000)throw new IllegalStateException("核查结果过大");
            var result=json.readTree(raw);
            if(!op.logicalId().equals(result.path("logicalId").asText())||!op.scopeKey().equals(result.path("scopeKey").asText()))throw new IllegalStateException("核查结果未关联到本次操作");
            var verified=new TencentOrderReceiptVerifier().verify(json.writeValueAsString(result.path("createReceipt")));
            String id=JsonFingerprint.hash(Map.of("operation",op.logicalId(),"raw",raw,"source","tencent-correlated-reconciliation-v1"));
            return new OperationReceipt(id,"tencent-correlated-reconciliation-v1","SUCCESS",raw.substring(0,Math.min(raw.length(),16000)),verified.outcome(),verified.businessId(),System.currentTimeMillis());
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
}
