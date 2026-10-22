package org.celanwang.mio.orchestration;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GoalLifecycleTest {
    @Test void controlsDoNotForgeUserTurnsOrResetTheIntentRevision()throws Exception{
        var goal=new GoalRecord("g","s",1,"original intent","original intent",1,1);
        var paused=goal.transition(GoalRecord.State.PAUSED,2);var resumed=paused.transition(GoalRecord.State.ACTIVE,3);
        assertThat(resumed.trustedIntent()).isEqualTo(goal.trustedIntent());assertThat(resumed.turns()).isEqualTo(goal.turns());assertThat(resumed.revision()).isEqualTo(1);assertThat(resumed.controlRevision()).isEqualTo(2);
        assertThatThrownBy(()->paused.appendUser("new intent",4)).isInstanceOf(GoalRecord.Stopped.class);
        var closed=resumed.transition(GoalRecord.State.CLOSED,4);assertThatThrownBy(()->closed.transition(GoalRecord.State.ACTIVE,5)).isInstanceOf(IllegalStateException.class);
        assertThat(closed.transition(GoalRecord.State.CLOSED,5)).isSameAs(closed);
        var json=new ObjectMapper();assertThat(json.readValue(json.writeValueAsString(closed),GoalRecord.class)).isEqualTo(closed);
        var legacy=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(goal);legacy.remove("state");legacy.remove("controlRevision");assertThat(json.treeToValue(legacy,GoalRecord.class).state()).isEqualTo(GoalRecord.State.ACTIVE);
    }
    @Test void aGoalGuardStillRejectsTheOldRunAfterPauseAndResume(){
        var current=new java.util.concurrent.atomic.AtomicReference<>(new GoalRecord("g","s",1,"intent","intent",1,1));var original=current.get();
        var budget=new RunBudget(new RunBudget.Limits(10,10,10,1000,1000,10000),Clock.systemUTC());
        budget.bindGoalGuard(()->{current.get().requireActive();if(current.get().controlRevision()!=original.controlRevision())throw new GoalRecord.Stopped();});
        current.set(current.get().transition(GoalRecord.State.PAUSED,2));assertThatThrownBy(budget::tool).isInstanceOf(GoalRecord.Stopped.class);
        current.set(current.get().transition(GoalRecord.State.ACTIVE,3));assertThatThrownBy(()->budget.beginModel(1)).isInstanceOf(GoalRecord.Stopped.class);assertThat(budget.usage().modelCalls()).isZero();
    }
}
