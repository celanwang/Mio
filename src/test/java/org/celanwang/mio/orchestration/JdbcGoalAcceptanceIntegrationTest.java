package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.celanwang.mio.orchestration.GoalAcceptanceFixtures.*;

@EnabledIfEnvironmentVariable(named="MIO_TEST_PG_URL",matches="jdbc:postgresql:.*")
class JdbcGoalAcceptanceIntegrationTest {
    JdbcTemplate admin,jdbc;javax.sql.DataSource source;String schema;
    final ObjectMapper json=new ObjectMapper();final long now=1_800_000_000_000L;final Clock clock=Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC);
    JdbcCoordinationStore store;JdbcExecutionLeaseStore leases;JdbcTaskQueue queue;JdbcGoalLifecycle lifecycle;JdbcGoalBudgetStore budgets;JdbcGoalAcceptance acceptance;
    GoalRecord goal;
    @BeforeEach void initialize() {
        var base=new DriverManagerDataSource(System.getenv("MIO_TEST_PG_URL"),System.getenv().getOrDefault("MIO_TEST_PG_USERNAME","postgres"),System.getenv().getOrDefault("MIO_TEST_PG_PASSWORD",""));
        admin=new JdbcTemplate(base);schema="mio_accept_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        source=new AbstractDataSource() {
            private Connection scope(Connection c)throws SQLException{try(var s=c.createStatement()){s.execute("SET search_path TO "+schema);return c;}catch(SQLException failure){c.close();throw failure;}}
            public Connection getConnection()throws SQLException{return scope(base.getConnection());}public Connection getConnection(String u,String p)throws SQLException{return scope(base.getConnection(u,p));}
        };
        jdbc=new JdbcTemplate(source);jdbc.execute("CREATE TABLE events(id TEXT PRIMARY KEY,ts BIGINT NOT NULL,session_id TEXT,node TEXT,type TEXT,title TEXT,detail TEXT)");
        store=new JdbcCoordinationStore(jdbc,source,json);store.initialize();leases=new JdbcExecutionLeaseStore(jdbc,source);leases.initialize();queue=new JdbcTaskQueue(jdbc,source,json,store,leases);queue.initialize();
        budgets=new JdbcGoalBudgetStore(jdbc,source,json);budgets.initialize();lifecycle=new JdbcGoalLifecycle(jdbc,source,store,queue);
        acceptance=new JdbcGoalAcceptance(jdbc,source,store,REGISTRY,json,clock);acceptance.initialize();acceptance.initialize();
        goal=new GoalRecord("g","s",1,"用户目标","用户目标",now,now);store.createGoal(goal);budgets.open("g",new GoalBudgetStore.Limits(20,20,20,10000));
    }
    @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}
    GoalAcceptance.Request request(long expected,TaskRecord task,GoalAcceptance.Check... checks){return new GoalAcceptance.Request("s",goal.revision(),goal.controlRevision(),expected,List.of(selection(task,List.of(checks))));}
    @Test void confirmedConditionsSurviveRestartAndDoNotChangeIntentApprovalsLeaseOrBudget() throws Exception {
        var task=quote("quote",goal,now,"900.0");store.createTask(task);var lease=leases.acquire("s",30000);var budget=budgets.find("g").orElseThrow();
        var saved=acceptance.confirm(request(0,task,new GoalAcceptance.Check("durationSeconds",GoalAcceptance.Operator.LTE,"900")));
        var restarted=new JdbcGoalAcceptance(jdbc,source,new JdbcCoordinationStore(jdbc,source,json),REGISTRY,json,clock);
        assertThat(restarted.find("g")).contains(saved);assertThat(restarted.snapshot("s").acceptance().state()).isEqualTo(GoalAcceptanceView.State.SATISFIED);
        assertThat(store.goal("s")).contains(goal);assertThat(store.task("quote")).contains(task);assertThat(leases.valid(lease)).isTrue();assertThat(budgets.find("g")).contains(budget);
        var audit=json.readTree(jdbc.queryForObject("SELECT detail FROM events WHERE type='goal_acceptance_confirmed'",String.class));
        assertThat(audit.path("source").asText()).isEqualTo("USER");assertThat(audit.path("criteria").path("revision").asLong()).isEqualTo(1);
    }
    @Test void concurrentConfirmationsHaveExactlyOneWinnerAndAuditFailureRollsBack() throws Exception {
        var task=quote("quote",goal,now,"900");store.createTask(task);var barrier=new CyclicBarrier(2);var winners=new AtomicInteger();var pool=Executors.newFixedThreadPool(2);
        try {
            var jobs=List.of(1,2).stream().map(i->pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);try{acceptance.confirm(request(0,task));winners.incrementAndGet();}catch(IllegalStateException conflict){}return true;})).toList();
            for(var job:jobs)job.get(10,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        assertThat(winners.get()).isEqualTo(1);var original=acceptance.find("g").orElseThrow();
        jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_acceptance CHECK(type <> 'goal_acceptance_confirmed') NOT VALID");
        assertThatThrownBy(()->acceptance.confirm(request(1,task,new GoalAcceptance.Check("durationSeconds",GoalAcceptance.Operator.LTE,"800")))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(acceptance.find("g")).contains(original);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE type='goal_acceptance_confirmed'",Integer.class)).isEqualTo(1);
    }
    @Test void intentAndControlConflictsRequireRefreshWhilePauseKeepsTheConfirmedScopeAndCloseMakesItReadOnly() {
        var task=quote("quote",goal,now,"900");store.createTask(task);acceptance.confirm(request(0,task));
        var paused=lifecycle.transition("s",0,GoalRecord.State.PAUSED);
        assertThat(acceptance.snapshot("s").acceptance().state()).isEqualTo(GoalAcceptanceView.State.SATISFIED);
        assertThatThrownBy(()->acceptance.confirm(request(1,task))).isInstanceOf(IllegalStateException.class);
        goal=paused;acceptance.confirm(request(1,task));var active=lifecycle.transition("s",1,GoalRecord.State.ACTIVE);goal=active;
        var changed=goal.appendUser("更改要求",now+1);store.saveGoal(goal,changed);
        assertThat(acceptance.snapshot("s").acceptance().state()).isEqualTo(GoalAcceptanceView.State.STALE);
        assertThatThrownBy(()->acceptance.confirm(request(2,task))).isInstanceOf(IllegalStateException.class);
        goal=changed;assertThatThrownBy(()->acceptance.confirm(request(2,task))).isInstanceOf(IllegalArgumentException.class);
        lifecycle.transition("s",2,GoalRecord.State.CLOSED);goal=store.goal("s").orElseThrow();
        assertThatThrownBy(()->acceptance.confirm(new GoalAcceptance.Request("s",goal.revision(),goal.controlRevision(),2L,List.of()))).isInstanceOf(IllegalStateException.class);
    }
    @Test void unknownUnselectedOperationBlocksUntilCorrelatedReceiptWithoutResetOrReplay() {
        var selected=quote("quote",goal,now,"900");store.createTask(selected);var unknown=unknown("unknown",goal,now);store.createTask(unknown);acceptance.confirm(request(0,selected));
        var before=budgets.find("g");assertThat(acceptance.snapshot("s").acceptance().state()).isEqualTo(GoalAcceptanceView.State.BLOCKED);
        store.recordReceipt(unknown.id(),"unknown-op",new OperationReceipt("proof","trusted-fixture","SUCCESS","raw",OperationOutcome.CONFIRMED_APPLIED,"resource",now),now);
        assertThat(acceptance.snapshot("s").acceptance().state()).isEqualTo(GoalAcceptanceView.State.SATISFIED);
        assertThat(store.task(unknown.id()).orElseThrow().status()).isEqualTo(TaskStatus.UNKNOWN);assertThat(budgets.find("g")).isEqualTo(before);
    }
    @Test void foreignTasksReportsAndUnregisteredOrNonFiniteFieldsNeverBecomeCriteria() {
        var task=quote("quote",goal,now,"900");store.createTask(task);var report=task("report",goal,"report",TaskScope.PREPARE,now).next(TaskStatus.COMPLETED,"done",List.of(),false,now);store.createTask(report);
        assertThatThrownBy(()->acceptance.confirm(request(0,report))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->acceptance.confirm(request(0,task,new GoalAcceptance.Check("rawSql",GoalAcceptance.Operator.EQ,"true")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->acceptance.confirm(request(0,task,new GoalAcceptance.Check("durationSeconds",GoalAcceptance.Operator.LTE,"Infinity")))).isInstanceOf(IllegalArgumentException.class);
        var other=new GoalRecord("other","other-session",1,"other","other",now,now);store.createGoal(other);var foreign=quote("foreign",other,now,"1");store.createTask(foreign);
        assertThatThrownBy(()->acceptance.confirm(request(0,foreign))).isInstanceOf(IllegalArgumentException.class);
        assertThat(acceptance.find("g")).isEmpty();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE type='goal_acceptance_confirmed'",Integer.class)).isZero();
    }
    @Test void clearingIsExplicitVersionedAndCannotTurnAnEmptyScopeIntoSuccess() {
        var task=quote("quote",goal,now,"900");store.createTask(task);acceptance.confirm(request(0,task));
        acceptance.confirm(new GoalAcceptance.Request("s",1L,0L,1L,List.of()));var view=acceptance.snapshot("s").acceptance();
        assertThat(view.state()).isEqualTo(GoalAcceptanceView.State.NOT_DEFINED);assertThat(view.revision()).isEqualTo(2);
        jdbc.update("UPDATE goal_acceptances SET content='{}' WHERE goal_id='g'");
        assertThatThrownBy(()->acceptance.snapshot("s")).isInstanceOf(IllegalStateException.class);
    }
}
