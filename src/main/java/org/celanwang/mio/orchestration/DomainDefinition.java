package org.celanwang.mio.orchestration;

import io.agentscope.core.tool.*;
import java.util.*;
import java.util.function.Supplier;

/** Trusted application extension point. Tools are rewrapped per task; service credentials are not exposed. */
public record DomainDefinition(AgentCapability capability,String prompt,Supplier<List<ToolBase>> tools,Supplier<CapabilityAvailability> availability) {
    public DomainDefinition(AgentCapability capability,String prompt,Supplier<List<ToolBase>> tools){
        this(capability,prompt,tools,()->tools.get().isEmpty()
                ?CapabilityAvailability.of(CapabilityAvailability.State.UNAVAILABLE,"NO_TOOLS","领域业务工具尚未接入")
                :CapabilityAvailability.of(CapabilityAvailability.State.UNKNOWN,"TOOLS_LOADED","工具已加载，业务可用性未经在线验证"));
    }
    public DomainDefinition {Objects.requireNonNull(capability);Objects.requireNonNull(prompt);Objects.requireNonNull(tools);Objects.requireNonNull(availability);}
    public CapabilityAvailability checkAvailability(){
        try{return Objects.requireNonNull(availability.get());}
        catch(RuntimeException failure){return CapabilityAvailability.of(CapabilityAvailability.State.UNAVAILABLE,"CHECK_FAILED","无法确认能力状态，已停止新的执行");}
    }
}
