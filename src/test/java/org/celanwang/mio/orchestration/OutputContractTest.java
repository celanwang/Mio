package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.model.dto.ChatRequest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OutputContractTest {
    TaskSpec spec(String key,String contract,TaskScope scope) {
        return new TaskSpec(key,"arbitrary-domain","取得用户要求的可信结果",null,null,scope,null,null,null,contract);
    }
    List<CapabilityOutputContract> outputs() {return List.of(CapabilityOutputContract.report(),CapabilityOutputContract.rideQuote(),CapabilityOutputContract.resourceReference());}
    DomainFixture fixture(List<OperationVerifier> verifiers) {
        FactExtractor extractor=new FactExtractor() {
            public String source(){return "trusted-test-quote";}
            public Optional<String> invalidatedKey(String tool,Map<String,Object> input){return tool.equals("query-meals")?Optional.of("ride.quote"):Optional.empty();}
            public Optional<Value> extract(String tool,Map<String,Object> input,String raw,long now){return tool.equals("query-meals")?Optional.of(new Value("ride_quote",Map.of("durationSeconds","900"),now+60000)):Optional.empty();}
        };
        return new DomainFixture(verifiers,outputs(),List.of(extractor));
    }
    @Test void arbitraryFinishTextCannotSatisfyEvidenceContractOrTriggerDependentWork() {
        var f=fixture(List.of());f.call("mio_finish_task",Map.of("text","我保证已经成功，报价有效"));
        var task=f.runtime.submit("root",spec("unsupported","ride-quote",TaskScope.PREPARE),f.budget).block();
        assertThat(task.status()).isEqualTo(TaskStatus.FAILED);assertThat(task.reply()).contains("输出契约未满足","ride.quote");
        var dependent=new TaskSpec("dependent","arbitrary-domain","后续分析",null,List.of(task.id()),TaskScope.PREPARE);
        assertThat(f.runtime.submit("root",dependent,f.budget).block().status()).isEqualTo(TaskStatus.STALE);
        assertThat(f.modelCalls.get()).isEqualTo(1);assertThat(f.reads.get()).isZero();assertThat(f.writes.get()).isZero();
    }
    @Test void actualWorkerProducesVerifiableQuoteButItsCompletionExpiresWithTheEvidence() {
        var f=fixture(List.of());f.call("query-meals",Map.of());f.call("mio_finish_task",Map.of("text","已取得报价"));
        var task=f.runtime.submit("root",spec("quote","ride-quote",TaskScope.PREPARE),f.budget).block();
        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);
        var assessment=f.runtime.summary(task).resultAssessment();assertThat(assessment.state()).isEqualTo(TaskResultAssessment.State.VERIFIED);
        assertThat(assessment.evidence()).hasSize(1);assertThat(assessment.evidence().get(0).evidenceId()).isNotBlank();
        long expires=TaskFacts.of(task).get(0).expiresAt();
        assertThat(TaskResultAssessment.current(task,f.registry,expires).state()).isEqualTo(TaskResultAssessment.State.EXPIRED_EVIDENCE);
        assertThat(TaskResultAssessment.current(task,f.registry,expires).missingFacts()).containsExactly("ride.quote");
        assertThat(f.inputs.get(0).stream().map(m->m.getTextContent()).reduce("",(a,b)->a+"\n"+b)).contains("输出契约","ride-quote");
    }
    @Test void registeredReceiptProvesOnlyResourceCreationAndUnknownReceiptCannotPass() {
        OperationVerifier verifier=new OperationVerifier() {
            public boolean supports(String tool){return tool.equals("create-order");}
            public Verification verify(String raw){return new Verification(OperationOutcome.CONFIRMED_APPLIED,"test-order","trusted-test-adapter");}
        };
        for(boolean verified:List.of(true,false)) {
            var f=fixture(verified?List.of(verifier):List.of());f.call("create-order",Map.of());
            var pending=f.runtime.submit("root",spec("create","resource-reference",TaskScope.REQUEST_APPROVAL),f.budget).block();
            assertThat(f.writes.get()).isZero();f.call("mio_finish_task",Map.of("text","创建已返回"));
            var task=f.runtime.approve("root",List.of(new ChatRequest.Confirmation(pending.approvals().get(0).id(),true)),f.budget).block().get(0);
            assertThat(task.status()).isEqualTo(verified?TaskStatus.COMPLETED:TaskStatus.UNKNOWN);
            var result=f.runtime.summary(task).resultAssessment();assertThat(result.state()).isEqualTo(verified?TaskResultAssessment.State.VERIFIED:TaskResultAssessment.State.NOT_FINISHED);
            assertThat(result.scope()).contains("不证明后续支付或履约");assertThat(f.writes.get()).isEqualTo(1);
        }
    }
    @Test void reportIsExplicitlyWeakerAndLegacySnapshotsDefaultToIt() throws Exception {
        var f=new DomainFixture();f.call("mio_finish_task",Map.of("text","完成工作"));
        var task=f.runtime.submit("root",f.spec("report",TaskScope.PREPARE),f.budget).block();
        assertThat(f.runtime.summary(task).resultAssessment().state()).isEqualTo(TaskResultAssessment.State.EXECUTION_ONLY);
        var json=new ObjectMapper();var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(task);((com.fasterxml.jackson.databind.node.ObjectNode)node.get("spec")).remove("outputContract");
        var legacy=json.treeToValue(node,TaskRecord.class);assertThat(legacy.spec().outputContract()).isEqualTo("report");
        assertThat(json.readValue(json.writeValueAsString(task),TaskRecord.class)).isEqualTo(task);
        assertThat(f.runtime.summary(legacy).resultAssessment().state()).isEqualTo(TaskResultAssessment.State.EXECUTION_ONLY);
    }
    @Test void lateReceiptCanVerifyResourceOutputWithoutReopeningAnInterruptedTask() {
        var f=fixture(List.of());var goal=f.runtime.goal("root");long now=System.currentTimeMillis();
        var task=new TaskRecord("late","root",goal.id(),goal.revision(),spec("late","resource-reference",TaskScope.REQUEST_APPROVAL),"child-late",
                f.registry.require("arbitrary-domain").executionVersion(),TaskStatus.RUNNING,"",null,List.of(),false,List.of(),0,now,now);
        task=task.withOperation(new OperationRecord("operation","call","create-order","hash",now,"SUBMITTING",""),now)
                .next(TaskStatus.UNKNOWN,"执行中断",List.of(),true,now);
        assertThat(TaskResultAssessment.current(task,f.registry,now).state()).isEqualTo(TaskResultAssessment.State.NOT_FINISHED);
        task=task.withReceipt("operation",new OperationReceipt("late-receipt","trusted-test-adapter","SUCCESS","raw",OperationOutcome.CONFIRMED_APPLIED,"resource",now),now);
        var result=TaskResultAssessment.current(task,f.registry,now);
        assertThat(result.state()).isEqualTo(TaskResultAssessment.State.VERIFIED);assertThat(result.evidence()).extracting(TaskResultAssessment.Evidence::evidenceId).containsExactly("late-receipt");
        assertThat(task.status()).isEqualTo(TaskStatus.UNKNOWN);assertThat(f.writes.get()).isZero();
    }
    @Test void unregisteredOrWrongScopeContractsFailBeforeAnyModelOrToolAndChangedDefinitionsDoNotReinterpretHistory() {
        var f=fixture(List.of());
        assertThatThrownBy(()->f.runtime.submit("root",spec("unknown","self-certified",TaskScope.PREPARE),f.budget).block()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->f.runtime.submit("root",spec("scope","ride-quote",TaskScope.REQUEST_APPROVAL),f.budget).block()).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.modelCalls.get()).isZero();
        f.call("query-meals",Map.of());f.call("mio_finish_task",Map.of("text","done"));var task=f.runtime.submit("root",spec("quote","ride-quote",TaskScope.PREPARE),f.budget).block();
        var changed=new AgentCapability("arbitrary-domain","领域","desc","test-skill","new-version",List.of(),List.of(),outputs());
        assertThat(TaskResultAssessment.current(task,new AgentRegistry(List.of(changed)),System.currentTimeMillis()).state()).isEqualTo(TaskResultAssessment.State.CONTRACT_CHANGED);
    }
}
