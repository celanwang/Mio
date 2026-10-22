package org.celanwang.mio.orchestration;

import java.util.Set;

/** A local observation, never an authorization grant or a live provider SLA. */
public record CapabilityAvailability(State state,String code,String message,Set<String> preparationTools,long checkedAt) {
    public enum State { READY, PREPARATION_REQUIRED, UNAVAILABLE, UNKNOWN }
    public CapabilityAvailability {
        if(state==null||code==null||!code.matches("[A-Z_]{1,64}")||message==null||message.length()>1000)throw new IllegalArgumentException("能力状态无效");
        preparationTools=Set.copyOf(preparationTools);
    }
    public static CapabilityAvailability of(State state,String code,String message){return new CapabilityAvailability(state,code,message,Set.of(),System.currentTimeMillis());}
    public void requireExecution(){if(state==State.UNAVAILABLE)throw new Unavailable(message);}
    public void requireTool(String name){requireExecution();if(state==State.PREPARATION_REQUIRED&&!preparationTools.contains(name))throw new Unavailable("能力尚待准备，请先完成授权或连接准备");}
    public static final class Unavailable extends IllegalStateException {public Unavailable(String message){super(message);}}
}
