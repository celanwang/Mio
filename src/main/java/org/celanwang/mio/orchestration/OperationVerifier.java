package org.celanwang.mio.orchestration;

/** Trusted, deterministic adapter. Never accepts a model assertion as provider evidence. */
public interface OperationVerifier {
    record Verification(OperationOutcome outcome,String businessId,String source) {}
    boolean supports(String tool);
    Verification verify(String raw);
}
