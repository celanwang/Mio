package org.celanwang.mio.orchestration;

import reactor.core.publisher.Mono;

/** Read-only correlation lookup. Cannot submit, cancel, or infer non-execution from absence. */
public interface OperationReconciler {
    boolean supports(OperationRecord operation);
    Mono<OperationReceipt> reconcile(TaskRecord task,OperationRecord operation);
}
