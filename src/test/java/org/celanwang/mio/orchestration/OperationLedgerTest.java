package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.security.PolicyEngine;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OperationLedgerTest {
    static final String RECEIPT="{\"exitCode\":0,\"output\":{\"result\":{\"status\":200,\"body\":{\"code\":0,\"data\":{\"orderId\":\"9007199254740995\"}}}}}";
    final InMemoryCoordinationStore store=new InMemoryCoordinationStore();
    final GoalRecord goal=new GoalRecord("g","root",1,"intent","intent",0,0);
    final OperationRecord operation=new OperationRecord("op","call","tencent_ride_create_order","hash",1,"SUBMITTING","");
    TaskRecord task(String id){return new TaskRecord(id,"root","g",1,new TaskSpec(id,"domain","goal",null,null,TaskScope.REQUEST_APPROVAL),"child","v1",TaskStatus.RUNNING,"",null,List.of(),false,List.of(),0,0,0);}
    TaskExecution execution(){
        store.createGoal(goal);var task=task("a");store.createTask(task);store.startOperation(task,operation,1);
        return new TaskExecution(store,store.task("a").orElseThrow(),goal,
                new RunBudget(new RunBudget.Limits(5,5,5,10000,10000,10000),Clock.systemUTC()),new PolicyEngine("shadow"),Clock.systemUTC(),Map.of(),List.of(new TencentOrderReceiptVerifier()));
    }
    @Test void strictProviderEnvelopeIsRequiredAndDoesNotInventNegativeEvidence(){
        var verifier=new TencentOrderReceiptVerifier();
        assertThat(verifier.verify(RECEIPT).outcome()).isEqualTo(OperationOutcome.CONFIRMED_APPLIED);
        for(String invalid:List.of("下单成功", "{}",RECEIPT.replace("200","500"),RECEIPT.replace("\"code\":0","\"code\":1"),
                RECEIPT.replace("\"code\":0","\"code\":18446744073709551616"),RECEIPT.replace("\"exitCode\":0","\"exitCode\":4294967296"),
                RECEIPT.replace("\"orderId\":\"9007199254740995\"","\"orderId\":null"),
                RECEIPT.replace("\"code\":0","\"code\":1,\"code\":0"))){
            assertThat(verifier.verify(invalid).outcome()).as(invalid).isEqualTo(OperationOutcome.UNKNOWN);
        }
    }
    @Test void cancellationCannotDiscardLateEvidenceOrRestartAnEndedTask(){
        var execution=execution();var old=store.task("a").orElseThrow();
        store.saveTask(old,old.next(TaskStatus.UNKNOWN,"interrupted",List.of(),true,2),"cancelled");
        execution.effectReturned("op",RECEIPT);
        var updated=store.task("a").orElseThrow();
        assertThat(updated.status()).isEqualTo(TaskStatus.UNKNOWN);assertThat(updated.unresolvedEffects()).isFalse();
        assertThat(updated.operations().get(0).businessId()).isEqualTo("9007199254740995");
        assertThatThrownBy(()->updated.next(TaskStatus.RUNNING,"retry",List.of(),true,4)).isInstanceOf(IllegalStateException.class);
        execution.effectReturned("op",RECEIPT);assertThat(store.task("a").orElseThrow()).isEqualTo(updated);
    }
    @Test void onlyVerifiedEvidenceReleasesTheDomainFenceForANewTask(){
        var execution=execution();var second=task("b");store.createTask(second);
        var other=new OperationRecord("op-b","other","write","h",1,"SUBMITTING","");
        assertThatThrownBy(()->store.startOperation(second,other,2)).isInstanceOf(IllegalStateException.class);
        execution.effectReturned("op",RECEIPT);store.startOperation(second,other,3);
        assertThat(store.task("b").orElseThrow().effectStarted()).isTrue();
    }
    @Test void frameworkErrorNeverPromotesAnOtherwiseSuccessfulLookingPayload(){
        var execution=execution();execution.effectReturned("op",RECEIPT,"ERROR");
        assertThat(store.task("a").orElseThrow().unresolvedEffects()).isTrue();
        assertThat(store.task("a").orElseThrow().operations().get(0).receipts()).hasSize(1);
    }
    @Test void conflictingEvidenceIsRetainedAndReturnsToUnknown(){
        var execution=execution();execution.effectReturned("op",RECEIPT);
        execution.effectReturned("op",RECEIPT.replace("9007199254740995","different-order"));
        var op=store.task("a").orElseThrow().operations().get(0);
        assertThat(op.outcome()).isEqualTo(OperationOutcome.UNKNOWN);assertThat(op.receipts()).hasSize(2);
    }
    @Test void legacyOperationJsonRemainsUnresolved()throws Exception{
        var old="{\"id\":\"op\",\"callId\":\"c\",\"tool\":\"write\",\"inputHash\":\"h\",\"startedAt\":1,\"phase\":\"SUBMITTING\",\"output\":\"\"}";
        var op=new ObjectMapper().readValue(old,OperationRecord.class);
        assertThat(op.outcome()).isEqualTo(OperationOutcome.UNKNOWN);assertThat(op.receipts()).isEmpty();
    }
    @Test void illegalStateTransitionsAreRejected(){
        var waiting=task("x").next(TaskStatus.NEEDS_INPUT,"ask",List.of(),false,1);
        assertThatThrownBy(()->waiting.next(TaskStatus.COMPLETED,"invented",List.of(),false,2)).isInstanceOf(IllegalStateException.class);
    }
    @Test void verifierFailureCannotDiscardTheOriginalReceipt(){
        execution();
        OperationVerifier broken=new OperationVerifier(){
            public boolean supports(String name){return true;}
            public Verification verify(String raw){throw new IllegalStateException("parser failed");}
        };
        var lease=new TaskExecution(store,store.task("a").orElseThrow(),goal,
                new RunBudget(new RunBudget.Limits(5,5,5,10000,10000,10000),Clock.systemUTC()),new PolicyEngine("shadow"),Clock.systemUTC(),Map.of(),List.of(broken));
        lease.effectReturned("op",RECEIPT);
        var op=store.task("a").orElseThrow().operations().get(0);
        assertThat(op.outcome()).isEqualTo(OperationOutcome.UNKNOWN);
        assertThat(op.receipts()).hasSize(1);assertThat(op.receipts().get(0).output()).isEqualTo(RECEIPT);
        assertThat(op.receipts().get(0).source()).isEqualTo("verifier-error");
        var fixed=new TaskExecution(store,store.task("a").orElseThrow(),goal,
                new RunBudget(new RunBudget.Limits(5,5,5,10000,10000,10000),Clock.systemUTC()),new PolicyEngine("shadow"),Clock.systemUTC(),Map.of(),List.of(new TencentOrderReceiptVerifier()));
        fixed.effectReturned("op",RECEIPT);
        var resolved=store.task("a").orElseThrow().operations().get(0);
        assertThat(resolved.receipts()).hasSize(2);assertThat(resolved.outcome()).isEqualTo(OperationOutcome.CONFIRMED_APPLIED);
    }
}
