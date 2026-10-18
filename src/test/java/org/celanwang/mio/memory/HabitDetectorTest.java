package org.celanwang.mio.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import java.sql.ResultSet;
import java.time.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class HabitDetectorTest {
    @ParameterizedTest
    @CsvSource({"query-my-coupons,2,2,false", "query-my-coupons,3,1,false", "query-my-coupons,3,2,true",
            "create-order,10,3,false", "unseen_write,10,3,false", "auto-bind-coupons,4,2,true"})
    void onlyRepeatedWhitelistedActionsAcrossDaysPropose(String tool, int calls, int days, boolean expected) {
        var jdbc = mock(JdbcTemplate.class);
        var store = mock(AutomationStore.class);
        doAnswer(inv -> {
            RowCallbackHandler callback = inv.getArgument(1);
            for (int i=0; i<calls; i++) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("node")).thenReturn("tool:" + tool);
                when(rs.getLong("ts")).thenReturn(LocalDate.now().minusDays(i % days)
                        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
                callback.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        var detector = new HabitDetector(true, store, Runnable::run, jdbc);
        detector.detectAsync();
        verify(store, times(expected ? 1 : 0)).propose(eq(tool), anyString(), anyString());
        detector.detectAsync();
        verify(jdbc, times(1)).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }
    @Test void disabledAndDatabaseFailuresCannotCreateAuthorization() {
        var jdbc = mock(JdbcTemplate.class);
        var store = mock(AutomationStore.class);
        new HabitDetector(false, store, Runnable::run, jdbc).detectAsync();
        verifyNoInteractions(jdbc, store);
        doThrow(new IllegalStateException("offline")).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        assertThatCode(() -> new HabitDetector(true, store, Runnable::run, jdbc).detectAsync()).doesNotThrowAnyException();
        verifyNoInteractions(store);
    }
    @Test void anyExistingRuleIncludingRejectionPreventsNewProposal() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        assertThat(new AutomationStore(jdbc).propose("query-my-coupons", "title", "reason")).isNull();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
