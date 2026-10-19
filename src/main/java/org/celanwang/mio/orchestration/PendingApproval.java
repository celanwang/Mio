package org.celanwang.mio.orchestration;

import java.util.Map;

/** Opaque public approval id maps to a specific child call and immutable parameter fingerprint. */
public record PendingApproval(String id, String callId, String tool, String description,
                              Map<String,Object> input, String inputHash, long expiresAt, Boolean decision) {
    public PendingApproval {
        input = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(input));
    }
    public PendingApproval decide(boolean approved) {
        return new PendingApproval(id,callId,tool,description,input,inputHash,expiresAt,approved);
    }
}
