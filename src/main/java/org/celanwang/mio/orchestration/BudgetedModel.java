package org.celanwang.mio.orchestration;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.*;
import reactor.core.publisher.Flux;
import java.util.List;

/** Reserve calls before networking; account cumulative streaming usage once before exposing tool calls. */
public final class BudgetedModel implements Model {
    private final Model delegate;
    private final RunBudget budget;
    public BudgetedModel(Model delegate, RunBudget budget) { this.delegate=delegate;this.budget=budget; }
    @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> {
            int chars=messages.stream().mapToInt(m -> m.toString().length()).sum();
            if (tools != null) chars+=tools.toString().length();
            budget.model(chars);
            return delegate.stream(messages,tools,options).collectList().flatMapMany(chunks -> {
                Long used=null;
                for (var chunk : chunks) if (chunk.getUsage()!=null) {
                    long current=(long)chunk.getUsage().getInputTokens()+chunk.getUsage().getOutputTokens();
                    used=used==null ? current : Math.max(used,current);
                }
                budget.tokens(used);budget.active();return Flux.fromIterable(chunks);
            });
        });
    }
    @Override public String getModelName(){return delegate.getModelName();}
    @Override public boolean supportsNativeStructuredOutput(){return delegate.supportsNativeStructuredOutput();}
    @Override public boolean supportsNativeStructuredOutputWithTools(){return delegate.supportsNativeStructuredOutputWithTools();}
    @Override public int getContextWindowSize(){return delegate.getContextWindowSize();}
}
