package org.celanwang.mio.orchestration;

import java.util.List;
import java.util.Objects;

/** Published by trusted application configuration, never by a planner or tool response. */
public record AgentCapability(String id, String name, String description, String skill, String version,
                              List<String> constraints) {
    public AgentCapability {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException("Invalid capability id");
        Objects.requireNonNull(name); Objects.requireNonNull(description);
        Objects.requireNonNull(skill); Objects.requireNonNull(version);
        constraints = List.copyOf(constraints);
    }
}
