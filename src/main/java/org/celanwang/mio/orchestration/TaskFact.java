package org.celanwang.mio.orchestration;

import java.util.Map;

/** Derived from persisted provider evidence, not model-generated assertions. */
public record TaskFact(String id,String key,String type,Map<String,String> value,String sourceTask,
                       String evidenceId,String version,long goalRevision,long observedAt,long expiresAt) {
    public TaskFact {value=Map.copyOf(value);}
}
