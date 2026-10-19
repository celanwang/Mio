package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ContractsTest {
    @Test void registryIsExtensibleAndRejectsDuplicateOrUnknownCapabilities(){
        var a=new AgentCapability("new-domain","新领域","可扩展能力","new-skill","1",List.of("人工确认写操作"));
        var registry=new AgentRegistry(List.of(a));
        assertThat(registry.require("new-domain")).isEqualTo(a);
        assertThatThrownBy(()->registry.require("invented")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new AgentRegistry(List.of(a,a))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void taskContractsAreImmutableAndRejectCyclesAtTheBoundary(){
        assertThatThrownBy(()->new TaskSpec("t","any","goal",List.of(),List.of("t"),null)).isInstanceOf(IllegalArgumentException.class);
        var deps=new ArrayList<>(List.of("other"));
        var task=new TaskSpec("t","any","goal",List.of(),deps,null);deps.clear();
        assertThat(task.dependencies()).containsExactly("other");assertThat(task.scope()).isEqualTo(TaskScope.PREPARE);
    }
    @Test void finishedTasksCannotReplayAndEffectsCannotBeErased() throws Exception {
        var t=new TaskRecord("t","s","g",1,new TaskSpec("key","any","goal",null,null,null),"child",TaskStatus.RUNNING,"",List.of(),true,1,1,1);
        assertThatThrownBy(()->t.next(TaskStatus.COMPLETED,"done",List.of(),false,2)).isInstanceOf(IllegalArgumentException.class);
        var ended=t.next(TaskStatus.UNKNOWN,"核查",List.of(),true,2);
        assertThatThrownBy(()->ended.next(TaskStatus.RUNNING,"again",List.of(),true,3)).isInstanceOf(IllegalStateException.class);
        var json=new ObjectMapper();assertThat(json.readValue(json.writeValueAsString(t),TaskRecord.class)).isEqualTo(t);
    }
    @Test void sharedBudgetBoundsResumptionsAndRejectsOversizedContext(){
        var b=new RunBudget(new RunBudget.Limits(2,2,2,100,20,1000),Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));
        b.model(10);b.model(10);
        assertThatThrownBy(()->b.model(10)).isInstanceOf(RunBudget.BudgetExceeded.class);
        b.tool();b.tool();assertThatThrownBy(b::tool).isInstanceOf(RunBudget.BudgetExceeded.class);
        assertThatThrownBy(()->b.model(21)).isInstanceOf(RunBudget.BudgetExceeded.class);
    }
    @Test void budgetExpiresAndMissingUsageIsNotPretendedToBeKnown(){
        Clock clock=mock(Clock.class);when(clock.millis()).thenReturn(0L,1000L);
        var b=new RunBudget(new RunBudget.Limits(1,1,1,100,100,1000),clock);
        assertThatThrownBy(b::active).isInstanceOf(RunBudget.BudgetExceeded.class);
        var a=new RunBudget(new RunBudget.Limits(1,1,1,100,100,1000),Clock.systemUTC());a.tokens(null);
        assertThat(a.usage().tokenUsageComplete()).isFalse();
    }
    @Test void modelBudgetStopsBeforeSecondNetworkCall(){
        Model model=mock(Model.class);
        when(model.stream(anyList(),any(),any())).thenReturn(Flux.just(ChatResponse.builder().content(List.of()).build()));
        var budget=new RunBudget(new RunBudget.Limits(1,1,1,100,10000,5000),Clock.systemUTC());
        var wrapper=new BudgetedModel(model,budget);
        wrapper.stream(List.of(new UserMessage("hello")),null,GenerateOptions.builder().build()).blockLast();
        assertThatThrownBy(()->wrapper.stream(List.of(new UserMessage("again")),null,null).blockLast()).isInstanceOf(RunBudget.BudgetExceeded.class);
        verify(model,times(1)).stream(anyList(),any(),any());
    }
    @Test void nestedApprovalParametersRemainIdenticalToTheSignedInput(){
        var nested=new ArrayList<Object>(List.of(new LinkedHashMap<>(Map.of("count",1))));
        var input=new LinkedHashMap<String,Object>();input.put("items",nested);
        var approval=new PendingApproval("a","c","tool","desc",input,JsonFingerprint.hash(input),"contract",1000,null);
        nested.clear();input.clear();
        assertThat(JsonFingerprint.hash(approval.input())).isEqualTo(approval.inputHash());
        assertThatThrownBy(()->((Map<String,Object>)((List<?>)approval.input().get("items")).get(0)).put("count",2)).isInstanceOf(UnsupportedOperationException.class);
    }
}
