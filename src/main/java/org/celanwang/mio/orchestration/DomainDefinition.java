package org.celanwang.mio.orchestration;

import io.agentscope.core.tool.*;
import java.util.*;
import java.util.function.Supplier;

/** Trusted application extension point. Tools are rewrapped per task; service credentials are not exposed. */
public record DomainDefinition(AgentCapability capability,String prompt,Supplier<List<ToolBase>> tools) {
    public DomainDefinition {Objects.requireNonNull(capability);Objects.requireNonNull(prompt);Objects.requireNonNull(tools);}
}
