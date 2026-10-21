package org.celanwang.mio.orchestration;

import org.junit.jupiter.api.Test;
import org.celanwang.mio.security.PolicyEngine;
import reactor.core.publisher.Mono;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class TaskAnswerTest {
    @Test void scopedAnswerResumesOnlyItsTaskAndKeepsUnrelatedApprovalValid(){
        var f=new DomainFixture();f.call("mio_request_input",Map.of("text","本次在哪个城市？"));
        var question=f.runtime.submit("root",f.spec("question",TaskScope.PREPARE),f.budget).block();
        f.call("create-order",Map.of());var pending=f.runtime.submit("root",f.spec("pending",TaskScope.REQUEST_APPROVAL),f.budget).block();
        var goal=f.runtime.goal("root");f.call("query-meals",Map.of());f.call("mio_finish_task",Map.of("text","done"));
        var result=f.runtime.answerTask("root",question.id(),question.questionId(),"上海",f.budget).block();
        assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED);assertThat(result.answers()).hasSize(1);
        assertThat(result.trustedIntent(goal)).contains("上海");assertThat(pending.trustedIntent(goal)).doesNotContain("上海");
        assertThat(f.runtime.require("root",pending.id())).isEqualTo(pending);assertThat(f.runtime.goal("root")).isEqualTo(goal);
        assertThat(result.spec().scope()).isEqualTo(TaskScope.PREPARE);
    }
    @Test void twoQuestionsRequireExplicitTargetAndEachKeepsItsOwnAnswer(){
        var f=new DomainFixture();f.call("mio_request_input",Map.of("text","第一个地点？"));
        var a=f.runtime.submit("root",f.spec("a",TaskScope.PREPARE),f.budget).block();
        f.call("mio_request_input",Map.of("text","第二个地点？"));var b=f.runtime.submit("root",f.spec("b",TaskScope.PREPARE),f.budget).block();
        f.runtime.captureUser("root","地点回答");
        assertThatThrownBy(()->f.runtime.continueTask("root",a.id(),f.budget).block()).isInstanceOf(IllegalStateException.class).hasMessageContaining("多个任务");
        f.call("mio_finish_task",Map.of("text","A done"));var answerA=f.runtime.answerTask("root",a.id(),a.questionId(),"上海",f.budget).block();
        assertThatThrownBy(()->f.runtime.continueTask("root",b.id(),f.budget).block()).isInstanceOf(IllegalStateException.class);
        f.call("mio_finish_task",Map.of("text","B done"));var answerB=f.runtime.answerTask("root",b.id(),b.questionId(),"北京",f.budget).block();
        assertThat(answerA.answers().get(0).text()).isEqualTo("上海");assertThat(answerB.answers().get(0).text()).isEqualTo("北京");
    }
    @Test void staleReplayedOrCrossSessionQuestionNeverReachesTheWorker(){
        var f=new DomainFixture();f.call("mio_request_input",Map.of("text","地点？"));var task=f.runtime.submit("root",f.spec("q",TaskScope.PREPARE),f.budget).block();
        int calls=f.modelCalls.get();
        assertThatThrownBy(()->f.runtime.answerTask("other",task.id(),task.questionId(),"上海",f.budget).block()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->f.runtime.answerTask("root",task.id(),"wrong","上海",f.budget).block()).isInstanceOf(IllegalStateException.class);
        assertThat(f.modelCalls.get()).isEqualTo(calls);
        f.call("mio_request_input",Map.of("text","具体在哪里？"));var again=f.runtime.answerTask("root",task.id(),task.questionId(),"上海",f.budget).block();
        assertThat(again.questionId()).isNotEqualTo(task.questionId());calls=f.modelCalls.get();
        assertThatThrownBy(()->f.runtime.answerTask("root",task.id(),task.questionId(),"重放",f.budget).block()).isInstanceOf(IllegalStateException.class);
        assertThat(f.modelCalls.get()).isEqualTo(calls);
    }
    @Test void explicitAnswerCannotTurnAPrepareTaskIntoAWriteTask(){
        var f=new DomainFixture();f.call("mio_request_input",Map.of("text","需求？"));var task=f.runtime.submit("root",f.spec("q",TaskScope.PREPARE),f.budget).block();
        f.call("create-order",Map.of());f.call("mio_finish_task",Map.of("text","done"));
        assertThat(f.runtime.answerTask("root",task.id(),task.questionId(),"帮我下单",f.budget).block().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(f.writes.get()).isZero();
    }
    @Test void answeringAQuestionCannotSubstituteForTheActualBusinessApproval(){
        var f=new DomainFixture();f.call("mio_request_input",Map.of("text","本次需求？"));var task=f.runtime.submit("root",f.spec("q",TaskScope.REQUEST_APPROVAL),f.budget).block();
        f.call("create-order",Map.of());
        var result=f.runtime.answerTask("root",task.id(),task.questionId(),"我同意下单",f.budget).block();
        assertThat(result.status()).isEqualTo(TaskStatus.AWAITING_APPROVAL);assertThat(result.approvals()).hasSize(1);
        assertThat(result.approvals().get(0).decision()).isNull();assertThat(f.writes.get()).isZero();
    }
    @Test void durableAcceptedAnswerCanResumeAfterProcessLossWithoutANewGlobalMessage(){
        var f=new DomainFixture();f.call("mio_request_input",Map.of("text","地点？"));var task=f.runtime.submit("root",f.spec("q",TaskScope.PREPARE),f.budget).block();
        var ready=task.answered(f.runtime.goal("root"),task.questionId(),"上海",1);f.store.saveTask(task,ready,"task_answer_received");
        var registry=new AgentRegistry(List.of(new AgentCapability("arbitrary-domain","领域","d","s","1",List.of())));var starts=new AtomicInteger();
        var restored=new TaskRuntime(f.store,registry,(t,g,i,e)->{starts.incrementAndGet();assertThat(i.userText()).isEqualTo("上海");assertThat(i.first()).isFalse();return Mono.just(new TaskWorker.Result("done",false,List.of(),null));},new PolicyEngine("shadow"),Clock.systemUTC(),16);
        assertThat(restored.continueTask("root",task.id(),f.budget).block().status()).isEqualTo(TaskStatus.COMPLETED);assertThat(starts.get()).isEqualTo(1);
    }
    @Test void cancellationAndChangedGoalsFenceAcceptedButNotYetStartedAnswers(){
        var f=new DomainFixture();f.call("mio_request_input",Map.of("text","地点？"));var task=f.runtime.submit("root",f.spec("q",TaskScope.PREPARE),f.budget).block();
        var ready=task.answered(f.runtime.goal("root"),task.questionId(),"上海",1);f.store.saveTask(task,ready,"task_answer_received");
        f.runtime.captureUser("root","取消原要求");assertThat(f.runtime.require("root",task.id()).status()).isEqualTo(TaskStatus.STALE);
        assertThatThrownBy(()->f.runtime.continueTask("root",task.id(),f.budget).block()).isInstanceOf(IllegalStateException.class);
    }
    @Test void oldJsonWithoutScopedAnswersAndInputRoutingRemainsReadable()throws Exception{
        var f=new DomainFixture();f.call("mio_request_input",Map.of("text","地点？"));var task=f.runtime.submit("root",f.spec("q",TaskScope.PREPARE),f.budget).block();
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(task);node.remove("answers");
        assertThat(json.treeToValue(node,TaskRecord.class).answers()).isEmpty();
        var goalNode=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(f.runtime.goal("root"));goalNode.remove("inputTaskId");
        assertThat(json.treeToValue(goalNode,GoalRecord.class).inputTaskId()).isNull();
    }
    @Test void oldTaskAnswersDoNotAppearAfterALaterGlobalCorrection(){
        var f=new DomainFixture();f.call("mio_request_input",Map.of("text","预算？"));var task=f.runtime.submit("root",f.spec("q",TaskScope.PREPARE),f.budget).block();
        var ready=task.answered(f.runtime.goal("root"),task.questionId(),"旧预算80元",1);
        var newer=f.runtime.goal("root").appendUser("新预算不得超过50元",2).appendUser("你好",3);
        var intent=ready.trustedIntent(newer);
        assertThat(intent.indexOf("旧预算80元")).isLessThan(intent.indexOf("新预算不得超过50元"));
        assertThat(intent.indexOf("新预算不得超过50元")).isLessThan(intent.indexOf("你好"));
        assertThat(ready.answers().get(0).goalRevision()).isEqualTo(task.goalRevision());
    }
    @Test void currentScopedAnswerFollowsItsGlobalTurnAndLegacySnapshotsAreNotSplitOnUserText()throws Exception{
        var f=new DomainFixture();f.call("mio_request_input",Map.of("text","预算？"));var task=f.runtime.submit("root",f.spec("q",TaskScope.PREPARE),f.budget).block();
        var newer=f.runtime.goal("root").appendUser("全局预算50元",2);
        var ready=task.answered(newer,task.questionId(),"本任务改为60元",3);var text=ready.trustedIntent(newer);
        assertThat(text.indexOf("全局预算50元")).isLessThan(text.indexOf("本任务改为60元"));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(newer);
        node.remove("turns");var legacy=json.treeToValue(node,GoalRecord.class);
        assertThat(legacy.turns()).hasSize(1);assertThat(legacy.turns().get(0).text()).isEqualTo(newer.trustedIntent());
    }
    @Test void inconsistentPersistedTimelineIsRejected(){
        assertThatThrownBy(()->new GoalRecord("g","root",1,"actual-user-text","actual-user-text",0,0,null,List.of(new GoalRecord.UserTurn(1,"altered")))).isInstanceOf(IllegalArgumentException.class);
    }
}
