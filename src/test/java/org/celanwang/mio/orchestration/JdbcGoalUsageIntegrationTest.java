package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** No providers or application credentials; each test owns its isolated PostgreSQL schema. */
@EnabledIfEnvironmentVariable(named="MIO_TEST_PG_URL",matches="jdbc:postgresql:.*")
class JdbcGoalUsageIntegrationTest {
    JdbcTemplate admin,jdbc;DriverManagerDataSource source;String schema;
    JdbcGoalBudgetStore budgets;final ObjectMapper json=new ObjectMapper();
    @BeforeEach void initialize(){
        String url=System.getenv("MIO_TEST_PG_URL"),user=System.getenv().getOrDefault("MIO_TEST_PG_USERNAME","postgres"),password=System.getenv().getOrDefault("MIO_TEST_PG_PASSWORD","");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));schema="mio_usage_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE events(id TEXT PRIMARY KEY,ts BIGINT NOT NULL,session_id TEXT,node TEXT,type TEXT,title TEXT,detail TEXT)");
        var coordination=new JdbcCoordinationStore(jdbc,source,json);coordination.initialize();coordination.createGoal(new GoalRecord("g","s",1,"intent","intent",1,1));
        budgets=new JdbcGoalBudgetStore(jdbc,source,json);budgets.initialize();budgets.open("g",new GoalBudgetStore.Limits(100,100,100,10000));
    }
    @AfterEach void cleanup(){if(admin!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}
    void expire(String call){jdbc.update("UPDATE budget_calls SET expires_at=0 WHERE id=?",call);}
    GoalBudgetStore.Snapshot state(){return budgets.find("g").orElseThrow();}
    String callState(String id){return jdbc.queryForObject("SELECT state FROM budget_calls WHERE id=?",String.class,id);}

    @Test void restartRecoversOnlyExpiredCallsInBoundedBatchesAndNeverRefundsQuota(){
        String first=budgets.reserveModel("g",900000),second=budgets.reserveModel("g",900000),live=budgets.reserveModel("g",900000);
        expire(first);expire(second);var restarted=new JdbcGoalBudgetStore(jdbc,source,json);
        assertThat(restarted.recoverExpired(1)).isEqualTo(1);assertThat(state().pendingUsage()).isEqualTo(2);assertThat(state().unknownUsage()).isEqualTo(1);
        assertThat(new GoalUsageRecovery(restarted).tick()).isEqualTo(1);assertThat(restarted.recoverExpired(100)).isZero();
        assertThat(state().models()).isEqualTo(3);assertThat(state().pendingUsage()).isEqualTo(1);assertThat(state().unknownUsage()).isEqualTo(2);
        assertThat(callState(live)).isEqualTo("PENDING");assertThat(state().usageComplete()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM events WHERE type='budget_usage_expired'",Long.class)).isEqualTo(2);
        assertThatThrownBy(()->restarted.recoverExpired(501)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void lateCompleteUsageAddsOnlyTheDifferenceAndCannotRewriteKnownOrDecreaseObservedUsage(){
        String call=budgets.reserveModel("g",900000);budgets.settle("g",call,7L,false);
        assertThat(state().knownTokens()).isEqualTo(7);assertThat(state().unknownUsage()).isEqualTo(1);
        assertThatThrownBy(()->budgets.settle("g",call,6L,true)).isInstanceOf(IllegalStateException.class);
        var before=state();jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_completion CHECK(id NOT LIKE '%:usage_completed')");
        assertThatThrownBy(()->budgets.settle("g",call,11L,true)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(state()).isEqualTo(before);assertThat(callState(call)).isEqualTo("UNKNOWN");jdbc.execute("ALTER TABLE events DROP CONSTRAINT reject_completion");
        budgets.settle("g",call,11L,true);var completed=state();budgets.settle("g",call,11L,true);
        assertThat(state()).isEqualTo(completed);assertThat(state().models()).isEqualTo(1);assertThat(state().knownTokens()).isEqualTo(11);assertThat(state().unknownUsage()).isZero();assertThat(state().usageComplete()).isTrue();
        assertThatThrownBy(()->budgets.settle("g",call,12L,true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->budgets.settle("g",call,null,false)).isInstanceOf(IllegalStateException.class);
    }
    @Test void recoveryAndLateCompletionAcrossInstancesChargeEachCallExactlyOnce() throws Exception {
        var other=new JdbcGoalBudgetStore(jdbc,source,json);var pool=Executors.newFixedThreadPool(2);
        try{for(int i=0;i<8;i++){
            String call=budgets.reserveModel("g",900000);expire(call);var barrier=new CyclicBarrier(2);
            var first=pool.submit(()->{barrier.await();return budgets.recoverExpired(100);});
            var second=pool.submit(()->{barrier.await();other.settle("g",call,11L,true);return true;});
            first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);assertThat(callState(call)).isEqualTo("KNOWN");
        }}finally{pool.shutdownNow();}
        assertThat(state().models()).isEqualTo(8);assertThat(state().knownTokens()).isEqualTo(88);assertThat(state().pendingUsage()).isZero();assertThat(state().unknownUsage()).isZero();
    }
    @Test void expiryAuditFailureLeavesPendingReservationForTheNextRecoveryAttempt(){
        String call=budgets.reserveModel("g",900000);expire(call);var before=state();
        jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_expiry CHECK(type<>'budget_usage_expired')");
        assertThatThrownBy(()->budgets.recoverExpired(100)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(state()).isEqualTo(before);assertThat(callState(call)).isEqualTo("PENDING");
        jdbc.execute("ALTER TABLE events DROP CONSTRAINT reject_expiry");assertThat(budgets.recoverExpired(100)).isEqualTo(1);assertThat(state().models()).isEqualTo(1);
    }
    @Test void migrationMakesLegacyPendingUnknownWithoutChangingKnownUsageOrHistoryCoverage(){
        String legacy=budgets.reserve("g",GoalBudgetStore.Kind.MODEL),known=budgets.reserve("g",GoalBudgetStore.Kind.MODEL);budgets.settle("g",known,7L,true);
        jdbc.execute("ALTER TABLE budget_calls DROP COLUMN expires_at CASCADE");budgets.initialize();budgets.initialize();
        assertThat(budgets.recoverExpired(100)).isEqualTo(1);assertThat(callState(legacy)).isEqualTo("UNKNOWN");assertThat(callState(known)).isEqualTo("KNOWN");
        assertThat(state().knownTokens()).isEqualTo(7);assertThat(state().models()).isEqualTo(2);assertThat(state().historyUnknown()).isFalse();
    }
    RunBudget run(Clock clock,long millis){
        var run=new RunBudget(new RunBudget.Limits(10,10,10,10000,10000,millis),clock);run.bind(budgets,"g",new GoalBudgetStore.Limits(100,100,100,10000));return run;
    }
    @Test void persistedDeadlineUsesDatabaseTimeAndOnlyRemainingRunDuration(){
        var appClock=mock(Clock.class);when(appClock.millis()).thenReturn(0L);var run=run(appClock,10000);when(appClock.millis()).thenReturn(4000L);
        long before=jdbc.queryForObject("SELECT floor(extract(epoch FROM clock_timestamp())*1000)::bigint",Long.class);run.beginModel(0);
        long after=jdbc.queryForObject("SELECT floor(extract(epoch FROM clock_timestamp())*1000)::bigint",Long.class);
        long expires=jdbc.queryForObject("SELECT expires_at FROM budget_calls",Long.class);assertThat(expires).isBetween(before+11000,after+11000);
        assertThatThrownBy(()->budgets.reserveModel("g",900001)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cancellationPersistsPartialUsageOnWorkerAndCleanupFailureRecoversAtDeadline() throws Exception {
        for(boolean reject:List.of(false,true)){
            if(reject)jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_cancel CHECK(type<>'budget_settled') NOT VALID");
            budgets=spy(new JdbcGoalBudgetStore(jdbc,source,json));var settlementDone=new CountDownLatch(1);
            doAnswer(inv->{try{return inv.callRealMethod();}finally{settlementDone.countDown();}}).when(budgets).settle(anyString(),anyString(),nullable(Long.class),eq(false));
            var observed=new CountDownLatch(1);var delivered=new AtomicInteger();var model=mock(Model.class);
            when(model.stream(anyList(),any(),any())).thenReturn(Flux.create(sink->{sink.next(ChatResponse.builder().content(List.of()).usage(ChatUsage.builder().inputTokens(5).outputTokens(2).build()).build());observed.countDown();}));
            var subscription=new BudgetedModel(model,run(Clock.systemUTC(),10000)).stream(List.of(),null,null).subscribe(value->delivered.incrementAndGet());
            assertThat(observed.await(2,TimeUnit.SECONDS)).isTrue();Mono.fromRunnable(subscription::dispose).subscribeOn(reactor.core.scheduler.Schedulers.parallel()).block();
            assertThat(settlementDone.await(3,TimeUnit.SECONDS)).isTrue();
            if(!reject){awaitPending(0);assertThat(state().knownTokens()).isEqualTo(7);assertThat(state().unknownUsage()).isEqualTo(1);}
            else {
                assertThat(state().pendingUsage()).isEqualTo(1);
                jdbc.execute("ALTER TABLE events DROP CONSTRAINT reject_cancel");jdbc.update("UPDATE budget_calls SET expires_at=0 WHERE state='PENDING'");new GoalUsageRecovery(budgets).tick();awaitPending(0);
                assertThat(state().unknownUsage()).isEqualTo(2);
            }
            assertThat(delivered.get()).isZero();verify(model,times(1)).stream(anyList(),any(),any());
        }
        assertThat(state().models()).isEqualTo(2);assertThat(state().knownTokens()).isEqualTo(7);assertThat(state().usageComplete()).isFalse();
    }
    void awaitPending(int expected) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<until&&state().pendingUsage()!=expected)Thread.sleep(10);
        assertThat(state().pendingUsage()).isEqualTo(expected);
    }
    @Test void busyBudgetIsSkippedUntilItsOwnerReleasesTheRow() throws Exception {
        String call=budgets.reserveModel("g",900000);expire(call);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try{
            var holding=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source)).execute(status->{
                jdbc.queryForObject("SELECT goal_id FROM goal_budgets WHERE goal_id='g' FOR UPDATE",String.class);entered.countDown();
                try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fixture lock timed out");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}return true;
            }));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();assertThat(budgets.recoverExpired(100)).isZero();assertThat(callState(call)).isEqualTo("PENDING");
            release.countDown();holding.get(5,TimeUnit.SECONDS);assertThat(budgets.recoverExpired(100)).isEqualTo(1);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void recoveredCallsAndLateTokenOverrunsCannotReopenQuota(){
        var coordination=new JdbcCoordinationStore(jdbc,source,json);coordination.createGoal(new GoalRecord("limited","limited",1,"intent","intent",1,1));
        budgets.open("limited",new GoalBudgetStore.Limits(1,2,2,10));String call=budgets.reserveModel("limited",900000);expire(call);budgets.recoverExpired(100);
        assertThatThrownBy(()->budgets.reserve("limited",GoalBudgetStore.Kind.MODEL)).isInstanceOf(RunBudget.BudgetExceeded.class);
        budgets.settle("limited",call,11L,true);
        assertThatThrownBy(()->budgets.reserve("limited",GoalBudgetStore.Kind.TOOL)).isInstanceOf(RunBudget.BudgetExceeded.class);
        var s=budgets.find("limited").orElseThrow();assertThat(s.models()).isEqualTo(1);assertThat(s.knownTokens()).isEqualTo(11);assertThat(s.pendingUsage()).isZero();assertThat(s.unknownUsage()).isZero();
    }
}
