package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import org.celanwang.mio.model.dto.ChatRequest;
import org.celanwang.mio.trace.TraceStore;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SupervisorIntegrationTest {
    static class Script {
        final Deque<List<ContentBlock>> turns=new ArrayDeque<>();final List<List<ToolSchema>> catalogs=new ArrayList<>();
        final Model model=mock(Model.class);
        Script(){when(model.getModelName()).thenReturn("fake-supervisor");when(model.stream(anyList(),any(),any())).thenAnswer(inv->Flux.defer(()->{
            catalogs.add(List.copyOf(inv.getArgument(1)));
            if(turns.isEmpty())return Flux.error(new IllegalStateException("root fixture exhausted"));
            return Flux.just(ChatResponse.builder().content(turns.removeFirst()).usage(ChatUsage.builder().inputTokens(10).outputTokens(5).build()).build());
        }));}
        void tool(String name,Map<String,Object> args)throws Exception{turns.add(List.of(ToolUseBlock.builder().id(UUID.randomUUID().toString()).name(name).input(args).content(new ObjectMapper().writeValueAsString(args)).build()));}
        void say(String text){turns.add(List.of(TextBlock.builder().text(text).build()));}
    }
    private MultiAgentChatService service(DomainFixture fixture,Script script){
        var registry=new AgentRegistry(List.of(new AgentCapability("arbitrary-domain","领域助手","任意注册能力","test-skill","1",List.of())));
        return new MultiAgentChatService(new SupervisorFactory(script.model,fixture.states,registry,fixture.runtime,new ObjectMapper()),fixture.runtime,registry,
                new RunBudget.Limits(50,100,30,100000,100000,120000),Clock.systemUTC(),new TraceStore((s,e)->{}),null,null);
    }
    private ChatRequest request(String message){var r=new ChatRequest();r.setSessionId("root");r.setMessage(message);return r;}
    @Test void actualSupervisorDiscoversDelegatesAndReceivesDomainResult()throws Exception{
        var fixture=new DomainFixture();var script=new Script();
        fixture.call("query-meals",Map.of());fixture.call("mio_finish_task",Map.of("text","已取得真实测试菜单"));
        script.tool("discover_agents",Map.of());script.tool("delegate_task",Map.of("key","menu","capabilityId","arbitrary-domain","objective","查看菜单"));script.say("菜单查询已结束");
        var response=service(fixture,script).chat(request("查菜单，不下单")).block(Duration.ofSeconds(10));
        assertThat(response.isMultiAgent()).isTrue();assertThat(response.getTasks()).hasSize(1);
        assertThat(response.getTasks().get(0).status()).isEqualTo(TaskStatus.COMPLETED);assertThat(fixture.reads.get()).isEqualTo(1);
        assertThat(script.catalogs.get(0).stream().map(ToolSchema::getName).toList()).contains("delegate_task").doesNotContain("query-meals","create-order");
        var state=fixture.states.get(null,"root:supervisor","agent_state",io.agentscope.core.state.AgentState.class).orElseThrow();
        String discovery=state.getContext().stream().flatMap(m->m.getContentBlocks(ToolResultBlock.class).stream())
                .filter(r->"discover_agents".equals(r.getName())).flatMap(r->r.getOutput().stream()).filter(b->b instanceof TextBlock)
                .map(b->((TextBlock)b).getText()).reduce("",String::concat);
        assertThat(discovery).contains("capability","availability","UNKNOWN");
    }
    @Test void publicApprovalRoutesToChildThenBackToPersistedSupervisor()throws Exception{
        var fixture=new DomainFixture();var script=new Script();fixture.call("create-order",Map.of());
        script.tool("delegate_task",Map.of("key","order","capabilityId","arbitrary-domain","objective","按用户要求下单","scope","REQUEST_APPROVAL"));script.say("请确认这项操作");
        var service=service(fixture,script);var pending=service.chat(request("帮我下单")).block(Duration.ofSeconds(10));
        assertThat(pending.getPendingConfirmations()).hasSize(1);assertThat(fixture.writes.get()).isZero();
        assertThat(pending.getPendingConfirmations().get(0).taskId()).isEqualTo(pending.getTasks().get(0).id());
        fixture.call("mio_finish_task",Map.of("text","工具已返回，仍需核查"));script.tool("list_tasks",Map.of());script.say("提交结果待核查");
        var confirm=request(null);confirm.setConfirmations(List.of(new ChatRequest.Confirmation(pending.getPendingConfirmations().get(0).toolCallId(),true)));
        var result=service.chat(confirm).block(Duration.ofSeconds(10));
        assertThat(fixture.writes.get()).isEqualTo(1);assertThat(result.getPendingConfirmations()).isEmpty();
        assertThat(result.getTasks().get(0).status()).isEqualTo(TaskStatus.UNKNOWN);
    }
    @Test void dependencyWaitsAndCancellationCannotBeResumed(){
        var f=new DomainFixture();f.call("mio_request_input",Map.of("text","请补充地点"));
        var first=f.runtime.submit("root",f.spec("first",TaskScope.PREPARE),f.budget).block(Duration.ofSeconds(10));
        var second=f.runtime.submit("root",new TaskSpec("second","arbitrary-domain","依赖任务",null,List.of(first.id()),null),f.budget).block(Duration.ofSeconds(10));
        assertThat(second.status()).isEqualTo(TaskStatus.WAITING_DEPENDENCY);assertThat(f.modelCalls.get()).isEqualTo(1);
        f.runtime.cancel("root",second.id());assertThat(f.runtime.require("root",second.id()).status()).isEqualTo(TaskStatus.CANCELLED);
        assertThatThrownBy(()->f.runtime.continueTask("root",second.id(),f.budget).block()).isInstanceOf(IllegalStateException.class);
    }
    @Test void scopedReplyUsesServerBrokerWithoutCreatingAGlobalUserMessageOrMemory()throws Exception{
        var f=new DomainFixture();f.call("mio_request_input",Map.of("text","城市？"));var task=f.runtime.submit("root",f.spec("q",TaskScope.PREPARE),f.budget).block();
        f.call("mio_finish_task",Map.of("text","按上海处理完成"));var script=new Script();script.tool("list_tasks",Map.of());script.say("已收到本任务回答");
        var registry=new AgentRegistry(List.of(new AgentCapability("arbitrary-domain","领域","d","s","1",List.of())));
        var service=new MultiAgentChatService(new SupervisorFactory(script.model,f.states,registry,f.runtime,new ObjectMapper()),f.runtime,registry,
                new RunBudget.Limits(50,100,30,100000,100000,120000),Clock.systemUTC(),new TraceStore((s,e)->{}),f.memory,null);
        var request=request("上海");request.setTaskReply(new ChatRequest.TaskReply(task.id(),task.questionId()));
        var before=f.runtime.goal("root");var response=service.chat(request).block(Duration.ofSeconds(10));
        assertThat(response.getTasks().get(0).status()).isEqualTo(TaskStatus.COMPLETED);assertThat(f.runtime.goal("root")).isEqualTo(before);
        assertThat(f.states.get(null,"root:supervisor","agent_state",io.agentscope.core.state.AgentState.class).orElseThrow().getContext().stream()
                .filter(m->m.getRole()==MsgRole.USER).map(Msg::getTextContent)).doesNotContain("上海");
        verifyNoInteractions(f.memory);
    }
    @Test void scopedReplyAndApprovalCannotBeCombined(){
        var f=new DomainFixture();var request=request("answer");request.setTaskReply(new ChatRequest.TaskReply("task","question"));
        request.setConfirmations(List.of(new ChatRequest.Confirmation("approval",true)));
        assertThatThrownBy(()->service(f,new Script()).chat(request).block()).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(f.modelCalls.get()).isZero();
    }
    @Test void publicResumeConsumesOnlyThePersistedAnswerAndCannotResumeOtherStates(){
        var f=new DomainFixture();f.call("mio_request_input",Map.of("text","城市？"));var task=f.runtime.submit("root",f.spec("q",TaskScope.PREPARE),f.budget).block();
        var ready=task.answered(f.runtime.goal("root"),task.questionId(),"上海",1);f.store.saveTask(task,ready,"task_answer_received");
        f.call("mio_finish_task",Map.of("text","done"));var script=new Script();script.say("已恢复");
        var request=request(null);request.setResumeTaskId(task.id());var service=service(f,script);var before=f.runtime.goal("root");
        assertThat(service.chat(request).block(Duration.ofSeconds(10)).getTasks().get(0).status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(f.runtime.goal("root")).isEqualTo(before);
        assertThatThrownBy(()->service.chat(request).block()).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
