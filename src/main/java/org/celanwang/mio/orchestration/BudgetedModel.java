package org.celanwang.mio.orchestration;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.*;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Reserve before networking; persist usage before any returned tool calls reach the agent. */
public final class BudgetedModel implements Model {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(BudgetedModel.class);
    private final Model delegate;private final RunBudget budget;
    public BudgetedModel(Model delegate,RunBudget budget){this.delegate=delegate;this.budget=budget;}
    @Override public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options){
        return Flux.defer(()->{
            int chars=io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(messages).length();
            if(tools!=null)chars=Math.addExact(chars,io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(tools).length());
            var call=budget.beginModel(chars);var used=new AtomicLong(-1);
            return Flux.defer(()->{budget.active();return delegate.stream(messages,tools,options);})
                    .doOnNext(chunk->{
                        if(chunk.getUsage()!=null){
                            int input=chunk.getUsage().getInputTokens(),output=chunk.getUsage().getOutputTokens();
                            if(input<0||output<0)throw new IllegalStateException("模型返回无效用量，已停止执行");
                            used.accumulateAndGet((long)input+output,Math::max);
                        }
                    }).publishOn(reactor.core.scheduler.Schedulers.boundedElastic()).collectList().flatMapMany(chunks->{
                        boolean complete=used.get()>=0&&!chunks.isEmpty()&&chunks.get(chunks.size()-1).getUsage()!=null;
                        if(complete){var last=chunks.get(chunks.size()-1).getUsage();complete=(long)last.getInputTokens()+last.getOutputTokens()==used.get();}
                        call.finish(used.get()<0?null:used.get(),complete);budget.active();return Flux.fromIterable(chunks);
                    }).onErrorResume(error->{
                        try{call.finish(used.get()<0?null:used.get(),false);}catch(Exception accounting){if(accounting!=error)error.addSuppressed(accounting);}
                        return Flux.error(error);
                    }).doFinally(signal->{
                        if(signal==reactor.core.publisher.SignalType.CANCEL) {
                            try{call.finish(used.get()<0?null:used.get(),false);}
                            catch(RunBudget.BudgetExceeded accounted){/* Usage is already durable; the cancelled stream cannot continue. */}
                            catch(Exception accounting){LOG.warn("取消调用用量未完成收尾，保留持久预占等待截止恢复：{}",accounting.getClass().getSimpleName());}
                        } else call.unfinished();
                    }).cancelOn(reactor.core.scheduler.Schedulers.boundedElastic());
        });
    }
    @Override public String getModelName(){return delegate.getModelName();}
    @Override public boolean supportsNativeStructuredOutput(){return delegate.supportsNativeStructuredOutput();}
    @Override public boolean supportsNativeStructuredOutputWithTools(){return delegate.supportsNativeStructuredOutputWithTools();}
    @Override public int getContextWindowSize(){return delegate.getContextWindowSize();}
}
