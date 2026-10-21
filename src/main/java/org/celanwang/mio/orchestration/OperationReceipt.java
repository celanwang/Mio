package org.celanwang.mio.orchestration;

/** Immutable evidence from the actual invocation, including late receipts after cancellation. */
public record OperationReceipt(String id,String source,String frameworkState,String output,
                               OperationOutcome outcome,String businessId,long observedAt) {
    public OperationReceipt {
        if(id==null||id.isBlank()||source==null||source.isBlank()||outcome==null)throw new IllegalArgumentException("回执无效");
        output=output==null?"":output;businessId=businessId==null?"":businessId;
        if(output.length()>16000)throw new IllegalArgumentException("回执过长");
        if(outcome!=OperationOutcome.UNKNOWN&&businessId.isBlank())throw new IllegalArgumentException("核验结果缺少业务标识");
    }
}
