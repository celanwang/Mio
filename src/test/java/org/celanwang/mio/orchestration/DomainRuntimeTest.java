package org.celanwang.mio.orchestration;

import org.celanwang.mio.model.dto.ChatRequest;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DomainRuntimeTest {
    private static final Duration TIMEOUT=Duration.ofSeconds(10);
    @Test void actualApprovalAndWorkerLoopUseTrustedReceiptAdapterToResolveBusinessOutcome(){
        OperationVerifier adapter=new OperationVerifier(){
            public boolean supports(String name){return name.equals("create-order");}
            public Verification verify(String raw){return new Verification(raw.equals("synthetic-receipt")?OperationOutcome.CONFIRMED_APPLIED:OperationOutcome.UNKNOWN,"synthetic-order","fixture-adapter");}
        };
        var f=new DomainFixture(List.of(adapter));f.call("create-order",Map.of());
        var task=f.runtime.submit("root",f.spec("verified",TaskScope.REQUEST_APPROVAL),f.budget).block(TIMEOUT);
        f.call("mio_finish_task",Map.of("text","创建已核实"));
        var result=f.runtime.approve("root",List.of(new ChatRequest.Confirmation(task.approvals().get(0).id(),true)),f.budget).block(TIMEOUT).get(0);
        assertThat(result.operations()).as(result.reply()).hasSize(1);
        assertThat(result.operations().get(0).receipts()).as(result.reply()).hasSize(1);
        assertThat(result.operations().get(0).outcome()).as(result.operations().toString()).isEqualTo(OperationOutcome.CONFIRMED_APPLIED);
        assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED);assertThat(result.unresolvedEffects()).isFalse();
        assertThat(result.operations().get(0).receipts()).hasSize(1);assertThat(f.writes.get()).isEqualTo(1);
    }
    @Test void realWorkerQueriesAndReturnsWhileKeepingDelegationOutOfUserMemory(){
        var f=new DomainFixture();f.call("query-meals",Map.of());f.call("mio_finish_task",Map.of("text","已查到菜单"));
        var record=f.runtime.submit("root",f.spec("menu",TaskScope.PREPARE),f.budget).block(TIMEOUT);
        assertThat(record.status()).isEqualTo(TaskStatus.COMPLETED);assertThat(record.reply()).isEqualTo("已查到菜单");
        assertThat(f.reads.get()).isEqualTo(1);assertThat(f.writes.get()).isZero();verifyNoInteractions(f.memory);
        f.runtime.submit("root",f.spec("menu",TaskScope.PREPARE),f.budget).block(TIMEOUT);
        assertThat(f.reads.get()).isEqualTo(1);
    }
    @Test void childQuestionRestoresItsOwnConversationAndUsesActualUserReply(){
        var f=new DomainFixture();f.call("mio_request_input",Map.of("text","本次在哪个城市？"));
        var task=f.runtime.submit("root",f.spec("trip",TaskScope.PREPARE),f.budget).block(TIMEOUT);
        assertThat(task.status()).isEqualTo(TaskStatus.NEEDS_INPUT);
        f.runtime.captureUser("root","上海");f.call("query-meals",Map.of());f.call("mio_finish_task",Map.of("text","已按上海处理"));
        var result=f.runtime.continueTask("root",task.id(),f.budget).block(TIMEOUT);
        assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(f.inputs.get(f.inputs.size()-1).stream().map(m->m.getTextContent()).toList()).contains("上海");
        assertThat(result.childSessionId()).isEqualTo(task.childSessionId());verifyNoInteractions(f.memory);
    }
    @Test void humanApprovalIsOpaqueTaskBoundSingleUseAndDoesNotClaimBusinessSuccess(){
        var f=new DomainFixture();f.call("create-order",Map.of());
        var task=f.runtime.submit("root",f.spec("order",TaskScope.REQUEST_APPROVAL),f.budget).block(TIMEOUT);
        assertThat(task.status()).isEqualTo(TaskStatus.AWAITING_APPROVAL);assertThat(f.writes.get()).isZero();
        var a=task.approvals().get(0);assertThat(a.id()).isNotEqualTo(a.callId());
        assertThatThrownBy(()->f.runtime.approve("other",List.of(new ChatRequest.Confirmation(a.id(),true)),f.budget).block(TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
        f.call("mio_finish_task",Map.of("text","工具已返回"));
        var result=f.runtime.approve("root",List.of(new ChatRequest.Confirmation(a.id(),true)),f.budget).block(TIMEOUT).get(0);
        assertThat(f.writes.get()).isEqualTo(1);assertThat(result.status()).isEqualTo(TaskStatus.UNKNOWN);
        assertThat(result.operations()).hasSize(1);assertThat(result.operations().get(0).phase()).isEqualTo("UNCONFIRMED");
        assertThatThrownBy(()->f.runtime.approve("root",List.of(new ChatRequest.Confirmation(a.id(),true)),f.budget).block(TIMEOUT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->f.runtime.submit("root",f.spec("duplicate",TaskScope.REQUEST_APPROVAL),f.budget).block(TIMEOUT)).isInstanceOf(IllegalStateException.class);
        assertThat(f.writes.get()).isEqualTo(1);
    }
    @Test void prepareTaskCannotWriteAndModelCannotReportDenialAsCompletion(){
        var f=new DomainFixture();f.call("create-order",Map.of());f.call("mio_finish_task",Map.of("text","done"));
        var record=f.runtime.submit("root",f.spec("query-only",TaskScope.PREPARE),f.budget).block(TIMEOUT);
        assertThat(record.status()).isEqualTo(TaskStatus.FAILED);assertThat(f.writes.get()).isZero();
    }
    @Test void changedToolContractInvalidatesAnOtherwiseCorrectApproval(){
        var f=new DomainFixture();f.call("create-order",Map.of());
        var task=f.runtime.submit("root",f.spec("order",TaskScope.REQUEST_APPROVAL),f.budget).block(TIMEOUT);
        f.toolDescription.set("changed-contract");f.call("mio_finish_task",Map.of("text","done"));
        var record=f.runtime.approve("root",List.of(new ChatRequest.Confirmation(task.approvals().get(0).id(),true)),f.budget).block(TIMEOUT).get(0);
        assertThat(f.writes.get()).isZero();assertThat(record.status()).isEqualTo(TaskStatus.FAILED);
    }
    @Test void newUserInformationInvalidatesOldPendingApproval(){
        var f=new DomainFixture();f.call("create-order",Map.of());
        var task=f.runtime.submit("root",f.spec("order",TaskScope.REQUEST_APPROVAL),f.budget).block(TIMEOUT);
        f.runtime.captureUser("root","先不要下单");
        assertThat(f.runtime.require("root",task.id()).status()).isEqualTo(TaskStatus.STALE);
        assertThatThrownBy(()->f.runtime.approve("root",List.of(new ChatRequest.Confirmation(task.approvals().get(0).id(),true)),f.budget).block(TIMEOUT)).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.writes.get()).isZero();
    }
    @Test void unknownCapabilityNeverStartsAWorker(){
        var f=new DomainFixture();
        assertThatThrownBy(()->f.runtime.submit("root",new TaskSpec("x","invented","goal",null,null,null),f.budget).block(TIMEOUT)).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.modelCalls.get()).isZero();
    }
    @Test void missingChildCheckpointCannotResumeAnApprovedWrite(){
        var f=new DomainFixture();f.call("create-order",Map.of());
        var task=f.runtime.submit("root",f.spec("missing-state",TaskScope.REQUEST_APPROVAL),f.budget).block(TIMEOUT);
        f.states.delete(null,task.childSessionId());int calls=f.modelCalls.get();
        var result=f.runtime.approve("root",List.of(new ChatRequest.Confirmation(task.approvals().get(0).id(),true)),f.budget).block(TIMEOUT).get(0);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);assertThat(f.writes.get()).isZero();assertThat(f.modelCalls.get()).isEqualTo(calls);
    }
    @Test void twoPreparedWriteTasksCannotBothSubmitInTheSameDomain(){
        var f=new DomainFixture();f.call("create-order",Map.of());f.call("create-order",Map.of());
        var a=f.runtime.submit("root",f.spec("a",TaskScope.REQUEST_APPROVAL),f.budget).block(TIMEOUT);
        var b=f.runtime.submit("root",f.spec("b",TaskScope.REQUEST_APPROVAL),f.budget).block(TIMEOUT);
        f.call("mio_finish_task",Map.of("text","returned"));
        f.runtime.approve("root",List.of(new ChatRequest.Confirmation(a.approvals().get(0).id(),true)),f.budget).block(TIMEOUT);
        f.call("mio_finish_task",Map.of("text","must not claim success"));
        var second=f.runtime.approve("root",List.of(new ChatRequest.Confirmation(b.approvals().get(0).id(),true)),f.budget).block(TIMEOUT).get(0);
        assertThat(second.status()).isEqualTo(TaskStatus.FAILED);assertThat(f.writes.get()).isEqualTo(1);
    }
    @Test void changedSourcePermissionMetadataAlsoInvalidatesExistingApproval(){
        var f=new DomainFixture();f.call("create-order",Map.of());
        var task=f.runtime.submit("root",f.spec("changed-flags",TaskScope.REQUEST_APPROVAL),f.budget).block(TIMEOUT);
        f.declaredReadOnly.set(true);f.call("mio_finish_task",Map.of("text","done"));
        var ended=f.runtime.approve("root",List.of(new ChatRequest.Confirmation(task.approvals().get(0).id(),true)),f.budget).block(TIMEOUT).get(0);
        assertThat(ended.status()).isEqualTo(TaskStatus.FAILED);assertThat(f.writes.get()).isZero();
    }
}
