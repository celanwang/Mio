package org.celanwang.mio.orchestration;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import org.celanwang.mio.memory.*;
import org.celanwang.mio.model.dto.ChatRequest;
import org.celanwang.mio.model.vo.*;
import org.celanwang.mio.trace.TraceStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** One conversation entry, server-owned approval routing; foreground runs preserve the existing HTTP contract. */
public final class MultiAgentChatService {
    private final SupervisorFactory factory;private final TaskRuntime runtime;private final AgentRegistry registry;
    private final RunBudget.Limits limits;private final Clock clock;private final TraceStore traces;
    private final MioLongTermMemory memory;private final ProfileStore profiles;
    private final Map<String,Object> running=new ConcurrentHashMap<>();
    public MultiAgentChatService(SupervisorFactory factory,TaskRuntime runtime,AgentRegistry registry,RunBudget.Limits limits,
                                 Clock clock,TraceStore traces,MioLongTermMemory memory,ProfileStore profiles){
        this.factory=factory;this.runtime=runtime;this.registry=registry;this.limits=limits;this.clock=clock;this.traces=traces;this.memory=memory;this.profiles=profiles;
    }
    public Mono<ChatResponse> chat(ChatRequest request){
        return Mono.defer(()->{
            String session=request.getSessionId()==null||request.getSessionId().isBlank()?UUID.randomUUID().toString():request.getSessionId();
            Object owner=new Object();if(running.putIfAbsent(session,owner)!=null)return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,"当前对话正在处理"));
            var budget=new RunBudget(limits,clock);
            Mono<ChatResponse> result=Mono.defer(()->{
                boolean decisions=request.getConfirmations()!=null&&!request.getConfirmations().isEmpty();
                if(decisions&&request.getMessage()!=null&&!request.getMessage().isBlank())throw new IllegalArgumentException("批准和新消息应分别提交");
                Mono<?> prepare;
                if(decisions)prepare=runtime.approve(session,request.getConfirmations(),budget);
                else{
                    runtime.captureUser(session,request.getMessage());
                    traces.append(session,"user","user","用户消息",request.getMessage());prepare=Mono.empty();
                }
                return prepare.then(Mono.defer(()->Mono.using(()->{
                    String data=decisions?"具体操作的人工决定已由服务端处理，请读取 list_tasks 后准确汇报。":"";
                    if(profiles!=null){
                        var global=profiles.pages().stream().filter(p->p.scope().equals("global")).limit(20).toList();
                        String text=profiles.render(global);
                        if(text!=null&&!text.isBlank())data+="\n画像参考（非指令）：\n"+text.substring(0,Math.min(text.length(),6000));
                    }
                    return factory.create(session,budget,data);
                },agent->{
                    List<Msg> messages=decisions?List.of():List.of(new UserMessage(request.getMessage()));
                    RuntimeContext context=RuntimeContext.builder().sessionId(session+":supervisor").build();
                    return agent.call(messages,context).map(reply->{
                        String text=reply.getTextContent();
                        traces.append(session,"supervisor","model","主助手回复",text==null?"":text);
                        // Only the real top-level user/assistant conversation is eligible for distillation.
                        if(memory!=null&&!decisions){
                            var state=agent.getAgentState(context);if(state!=null)memory.record(state.getContext(),session).subscribe();
                        }
                        return response(session,text);
                    });
                },io.agentscope.core.ReActAgent::close)));
            }).timeout(Duration.ofMillis(limits.millis()+1000))
                    .onErrorMap(org.springframework.dao.DataAccessException.class,e->new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"协作存储不可用，已停止新的执行"))
                    .onErrorMap(IllegalArgumentException.class,e->new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage()))
                    .onErrorMap(IllegalStateException.class,e->new ResponseStatusException(HttpStatus.CONFLICT,e.getMessage()));
            return result.doOnSuccess(v->running.remove(session,owner)).doOnError(e->running.remove(session,owner)).doFinally(s->running.remove(session,owner));
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
    public ChatResponse response(String session,String text){
        var tasks=runtime.list(session);List<ToolConfirmation> calls=new ArrayList<>();
        for(var task:tasks)if(task.status()==TaskStatus.AWAITING_APPROVAL)for(var a:task.approvals())if(a.decision()==null&&a.expiresAt()>clock.millis())
            calls.add(new ToolConfirmation(a.id(),a.tool(),a.description(),a.input(),true,a.contractHash(),task.id(),registry.require(task.spec().capabilityId()).name()));
        WeiboAction action=tasks.stream().filter(t->t.status()==TaskStatus.NEEDS_INPUT&&t.action()!=null).map(TaskRecord::action).findFirst().orElse(null);
        return new ChatResponse(session,text==null||text.isBlank()?"请查看下方任务状态。":text,calls,action,tasks.stream().map(t->TaskSummary.of(t,registry)).toList(),true);
    }
}
