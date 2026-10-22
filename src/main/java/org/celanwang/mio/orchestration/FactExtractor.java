package org.celanwang.mio.orchestration;

import java.util.*;

/** Application-owned adapter; neither model text nor task completion can assert a fact. */
public interface FactExtractor {
    String source();
    /** Called before invocation. A failed refresh or draft change must invalidate an older snapshot. */
    Optional<String> invalidatedKey(String tool,Map<String,Object> input);
    Optional<Value> extract(String tool,Map<String,Object> input,String raw,long now);
    record Value(String type,Map<String,String> value,long expiresAt){public Value{value=Map.copyOf(value);}}
}
