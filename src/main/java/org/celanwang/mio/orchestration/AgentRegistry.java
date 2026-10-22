package org.celanwang.mio.orchestration;

import java.util.*;

/** Domain implementations register here; orchestration never switches on provider names. */
public final class AgentRegistry {
    private final Map<String, AgentCapability> capabilities;
    private final Map<String,java.util.function.Supplier<CapabilityAvailability>> availability;
    public AgentRegistry(Collection<AgentCapability> entries) {
        this(entries,Map.of());
    }
    public AgentRegistry(Collection<AgentCapability> entries,Map<String,java.util.function.Supplier<CapabilityAvailability>> availability){
        Map<String,AgentCapability> result = new LinkedHashMap<>();
        for (var entry : entries) {
            if (result.putIfAbsent(entry.id(), entry) != null) throw new IllegalArgumentException("Duplicate capability: " + entry.id());
        }
        capabilities = Collections.unmodifiableMap(result);
        if(!result.keySet().containsAll(availability.keySet()))throw new IllegalArgumentException("能力状态引用了未注册能力");
        this.availability=Map.copyOf(availability);
    }
    public record Description(AgentCapability capability,CapabilityAvailability availability) {}
    public CapabilityAvailability status(String id){
        require(id);var source=availability.get(id);
        if(source==null)return CapabilityAvailability.of(CapabilityAvailability.State.UNKNOWN,"NOT_CHECKED","未提供本地可用性检查");
        try{return Objects.requireNonNull(source.get());}catch(RuntimeException failure){return CapabilityAvailability.of(CapabilityAvailability.State.UNAVAILABLE,"CHECK_FAILED","无法确认能力状态，已停止新的执行");}
    }
    public List<Description> discover(){return list().stream().map(c->new Description(c,status(c.id()))).toList();}
    public List<AgentCapability> list() { return List.copyOf(capabilities.values()); }
    public AgentCapability require(String id) {
        var value = capabilities.get(id);
        if (value == null) throw new IllegalArgumentException("未注册的能力：" + id);
        return value;
    }
}
