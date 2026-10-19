package org.celanwang.mio.orchestration;

import io.agentscope.core.hook.*;
import reactor.core.publisher.Mono;

final class DomainLifecycleHook implements Hook {
    private final TaskExecution execution;
    DomainLifecycleHook(TaskExecution execution){this.execution=execution;}
    @Override public <T extends HookEvent> Mono<T> onEvent(T event){
        if(event instanceof PostActingEvent post&&(execution.question()!=null||execution.finalAnswer()!=null))post.stopAgent();
        return Mono.just(event);
    }
}
