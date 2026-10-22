package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.service.TencentRideService;
import org.celanwang.mio.security.PolicyEngine;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationReconciliationTest {
    final String logical="11111111-1111-4111-8111-111111111111",scope="a".repeat(64);
    final ObjectMapper json=new ObjectMapper();
    final InMemoryCoordinationStore store=new InMemoryCoordinationStore();
    final RunBudget budget=new RunBudget(new RunBudget.Limits(20,20,20,10000,10000,10000),Clock.systemUTC());
    OperationRecord op(){return new OperationRecord("op","call","tencent_ride_create_order","hash",1,"SUBMITTING","",OperationOutcome.UNKNOWN,"",List.of(),logical,scope);}
    TaskRecord task(String id,String session){return new TaskRecord(id,session,"g-"+session,1,new TaskSpec(id,"domain","goal",null,null,TaskScope.REQUEST_APPROVAL),"child","1",TaskStatus.RUNNING,"",null,List.of(),false,List.of(),0,0,0);}
    TaskRecord start(String id,String session){store.createGoal(new GoalRecord("g-"+session,session,1,"intent","intent",0,0));var t=task(id,session);store.createTask(t);return t;}
    @Test void correlatedProofResolvesBusinessWithoutReplayingTheInterruptedTask()throws Exception{
        var service=mock(TencentRideService.class);var t=start("a","root");store.startOperation(t,op(),1);t=store.task("a").orElseThrow();store.saveTask(t,t.next(TaskStatus.UNKNOWN,"lost",List.of(),true,2),"interrupted");
        when(service.reconcile("child",logical,scope)).thenReturn(json.writeValueAsString(Map.of("logicalId",logical,"scopeKey",scope,"createReceipt",json.readTree(OperationLedgerTest.RECEIPT),"currentOrder","different or absent")));
        var runtime=new TaskRuntime(store,new AgentRegistry(List.of(new AgentCapability("domain","领域","d","s","1",List.of()))),(a,b,c,d)->{throw new AssertionError("must not execute worker");},new PolicyEngine("shadow"),Clock.systemUTC(),16,List.of(),List.of(new TencentOperationReconciler(service)));
        var result=runtime.reconcile("root","a","op",budget).block();assertThat(result.status()).isEqualTo(TaskStatus.UNKNOWN);assertThat(result.unresolvedEffects()).isFalse();assertThat(result.operations().get(0).businessId()).isEqualTo("9007199254740995");
        long revision=result.revision();runtime.reconcile("root","a","op",budget).block();assertThat(store.task("a").orElseThrow().revision()).isEqualTo(revision);
        assertThatThrownBy(()->runtime.reconcile("other","a","op",budget).block()).isInstanceOf(IllegalArgumentException.class);
        verify(service,times(2)).reconcile("child",logical,scope);
    }
    @Test void missingCorrelationCannotBeFilledInByAnUnrelatedCurrentOrder()throws Exception{
        var service=mock(TencentRideService.class);var t=task("a","root");
        when(service.reconcile("child",logical,scope)).thenReturn(json.writeValueAsString(Map.of("logicalId",logical,"scopeKey",scope,"currentOrder",json.readTree(OperationLedgerTest.RECEIPT))));
        assertThat(new TencentOperationReconciler(service).reconcile(t,op()).block().outcome()).isEqualTo(OperationOutcome.UNKNOWN);
        when(service.reconcile("child",logical,scope)).thenReturn("{\"logicalId\":\"different\",\"scopeKey\":\""+scope+"\"}");
        assertThatThrownBy(()->new TencentOperationReconciler(service).reconcile(t,op()).block()).isInstanceOf(IllegalStateException.class);
    }
    @Test void unresolvedScopeBlocksOtherSessionsAndReopensOnConflictingEvidence(){
        var a=start("a","one");store.startOperation(a,op(),1);var b=start("b","two");
        var second=new OperationRecord("op-b","call-b","tencent_ride_create_order","hash",1,"SUBMITTING","",OperationOutcome.UNKNOWN,"",List.of(),UUID.randomUUID().toString(),scope);
        assertThatThrownBy(()->store.startOperation(b,second,2)).isInstanceOf(IllegalStateException.class);
        store.recordReceipt("a","op",new OperationReceipt("proof","test","SUCCESS","raw",OperationOutcome.CONFIRMED_APPLIED,"order-a",3),3);
        store.startOperation(b,second,4);assertThat(store.task("b").orElseThrow().effectStarted()).isTrue();
        store.recordReceipt("a","op",new OperationReceipt("conflict","test","SUCCESS","raw2",OperationOutcome.CONFIRMED_APPLIED,"different",5),5);
        assertThat(store.task("a").orElseThrow().unresolvedEffects()).isTrue();
    }
}
