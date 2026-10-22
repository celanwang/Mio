package org.celanwang.mio.orchestration;

import java.util.*;

/** Business outcome evolves independently from the execution lifecycle; receipts are append-only. */
public record OperationRecord(String id,String callId,String tool,String inputHash,long startedAt,
                              String phase,String output,OperationOutcome outcome,String businessId,List<OperationReceipt> receipts,String logicalId,String scopeKey) {
    public OperationRecord {
        outcome=outcome==null?OperationOutcome.UNKNOWN:outcome;
        businessId=businessId==null?"":businessId;receipts=receipts==null?List.of():List.copyOf(receipts);
        logicalId=logicalId==null?id:logicalId;scopeKey=scopeKey==null?"":scopeKey;
        if(!scopeKey.isEmpty()&&!scopeKey.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("操作范围无效");
        if(!scopeKey.isEmpty()&&!UUID.fromString(logicalId).toString().equals(logicalId))throw new IllegalArgumentException("逻辑操作编号无效");
    }
    public OperationRecord(String id,String callId,String tool,String inputHash,long startedAt,String phase,String output,OperationOutcome outcome,String businessId,List<OperationReceipt> receipts){this(id,callId,tool,inputHash,startedAt,phase,output,outcome,businessId,receipts,id,"");}
    public OperationRecord(String id,String callId,String tool,String inputHash,long startedAt,String phase,String output){
        this(id,callId,tool,inputHash,startedAt,phase,output,OperationOutcome.UNKNOWN,"",List.of());
    }
    public OperationRecord received(OperationReceipt receipt){
        if(receipts.stream().anyMatch(r->r.id().equals(receipt.id())))return this;
        var history=new ArrayList<>(receipts);history.add(receipt);
        var known=history.stream().filter(r->r.outcome()!=OperationOutcome.UNKNOWN).toList();
        boolean conflict=known.stream().anyMatch(r->r.outcome()!=known.get(0).outcome()||!r.businessId().equals(known.get(0).businessId()));
        var resolved=known.isEmpty()||conflict?OperationOutcome.UNKNOWN:known.get(0).outcome();
        return new OperationRecord(id,callId,tool,inputHash,startedAt,resolved==OperationOutcome.UNKNOWN?"UNCONFIRMED":"VERIFIED",
                receipt.output(),resolved,resolved==OperationOutcome.UNKNOWN?"":known.get(0).businessId(),history,logicalId,scopeKey);
    }
}
