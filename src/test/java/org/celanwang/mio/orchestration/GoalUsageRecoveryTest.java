package org.celanwang.mio.orchestration;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoalUsageRecoveryTest {
    @Test void onlyApplicationReadyStartsRecoveryAndTheBatchSizeIsFixed() throws Exception {
        var store=mock(JdbcGoalBudgetStore.class);var entered=new CountDownLatch(1);
        when(store.recoverExpired(100)).thenAnswer(inv->{entered.countDown();return 2;});
        var recovery=new GoalUsageRecovery(store);
        try{verifyNoInteractions(store);recovery.start();recovery.start();assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();verify(store,times(1)).recoverExpired(100);}finally{recovery.stop();}
    }
    @Test void failedBookkeepingIsRetriedByTheNextTick(){
        var store=mock(JdbcGoalBudgetStore.class);when(store.recoverExpired(100)).thenThrow(new IllegalStateException("fixture storage down")).thenReturn(1);
        var recovery=new GoalUsageRecovery(store);
        try{assertThatThrownBy(recovery::tick).isInstanceOf(IllegalStateException.class);assertThat(recovery.tick()).isEqualTo(1);}finally{recovery.stop();}
    }
}
