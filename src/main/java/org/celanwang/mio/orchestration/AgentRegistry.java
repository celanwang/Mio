package org.celanwang.mio.orchestration;

import java.util.*;

/** Domain implementations register here; orchestration never switches on provider names. */
public final class AgentRegistry {
    private final Map<String, AgentCapability> capabilities;
    public AgentRegistry(Collection<AgentCapability> entries) {
        Map<String,AgentCapability> result = new LinkedHashMap<>();
        for (var entry : entries) {
            if (result.putIfAbsent(entry.id(), entry) != null) throw new IllegalArgumentException("Duplicate capability: " + entry.id());
        }
        capabilities = Collections.unmodifiableMap(result);
    }
    public List<AgentCapability> list() { return List.copyOf(capabilities.values()); }
    public AgentCapability require(String id) {
        var value = capabilities.get(id);
        if (value == null) throw new IllegalArgumentException("未注册的能力：" + id);
        return value;
    }
}
