package org.celanwang.mio.orchestration;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.celanwang.mio.orchestration.GoalAcceptanceFixtures.*;

class GoalAcceptanceTest {
    final long now=1_800_000_000_000L;
    final InMemoryCoordinationStore store=new InMemoryCoordinationStore();
    final GoalRecord goal=new GoalRecord("g","s",1,"用户目标","用户目标",now,now);
    GoalAcceptanceTest(){store.createGoal(goal);}
    GoalAcceptanceView view(GoalAcceptance confirmed,long at){return GoalAcceptanceView.evaluate(store.goal("s").orElseThrow(),store.tasks("s"),confirmed,REGISTRY,store,at);}
    GoalAcceptance confirmed(TaskRecord task,GoalAcceptance.Check... checks){return new GoalAcceptance(1,"g",1,1,now,List.of(requirement(task,List.of(checks))));}
    @Test void finishedTasksAloneNeverDefineGoalSuccessAndDecimalBoundariesUseExactArithmetic() {
        var task=quote("quote",goal,now,"900.00");store.createTask(task);
        assertThat(view(null,now).state()).isEqualTo(GoalAcceptanceView.State.NOT_DEFINED);
        var criteria=confirmed(task,new GoalAcceptance.Check("durationSeconds",GoalAcceptance.Operator.LTE,"900"));
        assertThat(view(criteria,now).state()).isEqualTo(GoalAcceptanceView.State.SATISFIED);
        assertThat(view(criteria,now).results().get(0).checks().get(0).actual()).isEqualTo("900.00");
        assertThat(view(criteria,now).validUntil()).isEqualTo(now+60000);
        assertThat(view(confirmed(task,new GoalAcceptance.Check("durationSeconds",GoalAcceptance.Operator.LTE,"899.999999999999")),now).state()).isEqualTo(GoalAcceptanceView.State.NOT_SATISFIED);
        assertThat(view(criteria,now+60000).results().get(0).state()).isEqualTo("EXPIRED_EVIDENCE");
    }
    @Test void newIntentRequiresExplicitReconfirmationAndPendingWorkPreventsOverallSatisfaction() {
        var task=quote("quote",goal,now,"900");store.createTask(task);var criteria=confirmed(task);
        store.createTask(task("pending",goal,"report",TaskScope.PREPARE,now));
        assertThat(view(criteria,now).state()).isEqualTo(GoalAcceptanceView.State.PENDING);
        var next=goal.appendUser("修改时间限制",now+1);store.saveGoal(goal,next);
        assertThat(view(criteria,now+1).state()).isEqualTo(GoalAcceptanceView.State.STALE);
    }
    @Test void unknownEffectsOutsideSelectedTasksBlockSuccessAndLateProofDoesNotReplayAnything() {
        var task=quote("quote",goal,now,"900");store.createTask(task);
        var unknown=unknown("unknown",goal,now);store.createTask(unknown);
        assertThat(view(confirmed(task),now).state()).isEqualTo(GoalAcceptanceView.State.BLOCKED);
        var criteria=confirmed(unknown,new GoalAcceptance.Check("businessId",GoalAcceptance.Operator.EQ,"resource"));
        store.recordReceipt(unknown.id(),"unknown-op",new OperationReceipt("proof","trusted-fixture","SUCCESS","raw",OperationOutcome.CONFIRMED_APPLIED,"resource",now),now);
        assertThat(view(criteria,now).state()).isEqualTo(GoalAcceptanceView.State.SATISFIED);
        assertThat(store.task(unknown.id()).orElseThrow().status()).isEqualTo(TaskStatus.UNKNOWN);
    }
    @Test void malformedProviderValuesOrMissingFieldsNeverPassAndPartialResultsRemainVisible() {
        var valid=quote("valid",goal,now,"900");var invalid=quote("invalid",goal,now,"NaN");store.createTask(valid);store.createTask(invalid);
        var criteria=new GoalAcceptance(1,"g",1,1,now,List.of(requirement(valid,List.of()),requirement(invalid,List.of(new GoalAcceptance.Check("durationSeconds",GoalAcceptance.Operator.LTE,"900")))));
        var result=view(criteria,now);assertThat(result.state()).isEqualTo(GoalAcceptanceView.State.PARTIALLY_SATISFIED);
        assertThat(result.results()).extracting(GoalAcceptanceView.RequirementResult::state).containsExactly("SATISFIED","INVALID_VALUE");
    }
    @Test void oldIntentUnknownEffectsStillBlockAcceptanceOfANewerIntent() {
        store.createTask(unknown("old-operation",goal,now));
        var changed=goal.appendUser("本次只验收新报价",now+1);store.saveGoal(goal,changed);
        var current=quote("new-quote",changed,now+1,"900");store.createTask(current);
        var criteria=new GoalAcceptance(1,"g",2,1,now+1,List.of(requirement(current,List.of())));
        assertThat(view(criteria,now+1).state()).isEqualTo(GoalAcceptanceView.State.BLOCKED);
        assertThat(view(criteria,now+1).blockers()).extracting(GoalAcceptanceView.Blocker::taskId).containsExactly("old-operation");
    }
    @Test void invalidExpectedNumbersUnsupportedComparisonsAndMissingVersionsAreRejected() {
        var numeric=CapabilityOutputContract.rideQuote().fields().get(0);
        for(String invalid:List.of("NaN","Infinity","1e3"," 900","+900","1,000","0.0000000000001","9".repeat(31)))
            assertThatThrownBy(()->new GoalAcceptance.Check("durationSeconds",GoalAcceptance.Operator.LTE,invalid).validate(numeric)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new GoalAcceptance.Check("businessId",GoalAcceptance.Operator.LTE,"resource").validate(CapabilityOutputContract.resourceReference().fields().get(0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new GoalAcceptance.Request("s",1L,0L,0L,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new GoalAcceptance.Request("s",1L,null,0L,List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new GoalAcceptance(2,"g",1,1,now,List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void revokedContractsAndDependencyInvalidationCannotReuseOldSuccess() {
        var parent=quote("parent",goal,now,"900");store.createTask(parent);var fact=TaskFacts.of(parent).get(0);
        var spec=new TaskSpec("dependent","domain","结果依赖报价",null,List.of(parent.id()),TaskScope.PREPARE,List.of(new InputBinding(parent.id(),fact.id(),fact.version(),"quote")),null,null,"ride-quote");
        var child=new TaskRecord("dependent","s","g",1,spec,"child",CAPABILITY.executionVersion(),TaskStatus.COMPLETED,"done",null,List.of(),false,List.of(),0,now,now);
        store.createTask(child);assertThat(view(confirmed(child),now+60000).results().get(0).state()).isEqualTo("INVALIDATED");
        var removed=new AgentRegistry(List.of(new AgentCapability("domain","changed","changed","skill","v2",List.of())));
        assertThat(GoalAcceptanceView.evaluate(goal,store.tasks("s"),confirmed(parent),removed,store,now).state()).isEqualTo(GoalAcceptanceView.State.NOT_SATISFIED);
    }
}
