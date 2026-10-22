package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Opt-in, real PostgreSQL only. Each test creates and drops its own isolated schema. */
@EnabledIfEnvironmentVariable(named="MIO_TEST_PG_URL",matches="jdbc:postgresql:.*")
class JdbcPostgresIntegrationTest {
    private JdbcTemplate admin,jdbc;
    private JdbcCoordinationStore store;
    private String schema;
    @BeforeEach void initialize(){
        var base=new DriverManagerDataSource(System.getenv("MIO_TEST_PG_URL"),
                System.getenv().getOrDefault("MIO_TEST_PG_USERNAME","postgres"),System.getenv().getOrDefault("MIO_TEST_PG_PASSWORD",""));
        admin=new JdbcTemplate(base);schema="mio_eval_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE SCHEMA "+schema);
        DataSource isolated=new AbstractDataSource(){
            private Connection scope(Connection connection)throws SQLException{
                try(var statement=connection.createStatement()){statement.execute("SET search_path TO "+schema);return connection;}
                catch(SQLException failure){connection.close();throw failure;}
            }
            public Connection getConnection()throws SQLException{return scope(base.getConnection());}
            public Connection getConnection(String user,String password)throws SQLException{return scope(base.getConnection(user,password));}
        };
        jdbc=new JdbcTemplate(isolated);
        jdbc.execute("CREATE TABLE events(id TEXT PRIMARY KEY, ts BIGINT NOT NULL, session_id TEXT, node TEXT, type TEXT, title TEXT, detail TEXT)");
        store=new JdbcCoordinationStore(jdbc,isolated,new ObjectMapper());store.initialize();
        store.createGoal(new GoalRecord("g","root",1,"intent","intent",0,0));
    }
    @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}
    TaskRecord task(String id){return new TaskRecord(id,"root","g",1,new TaskSpec(id,"domain","objective",null,null,TaskScope.REQUEST_APPROVAL),
            "child-"+id,"v1",TaskStatus.RUNNING,"",null,List.of(),false,List.of(),0,0,0);}
    @Test void rowLockAllowsOnlyOneConcurrentDomainSubmission()throws Exception{
        var a=task("a");var b=task("b");store.createTask(a);store.createTask(b);
        var barrier=new CyclicBarrier(2);var committed=new AtomicInteger();var pool=Executors.newFixedThreadPool(2);
        try{
            var jobs=List.of(a,b).stream().map(task->pool.submit(()->{
                barrier.await(5,TimeUnit.SECONDS);
                try{store.startOperation(task,new OperationRecord(task.id()+":call","call","write","hash",1,"SUBMITTING",""),1);committed.incrementAndGet();}
                catch(IllegalStateException blocked){assertThat(blocked).hasMessageContaining("同领域");}
                return true;
            })).toList();
            for(var job:jobs)job.get(10,TimeUnit.SECONDS);
            assertThat(committed.get()).isEqualTo(1);
            assertThat(store.tasks("root").stream().filter(TaskRecord::effectStarted).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE type = 'operation_started'",Integer.class)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void auditFailureRollsBackStateAndSdkCheckpointSurvivesNewStoreInstance(){
        var task=task("a");store.createTask(task);
        jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_test_event CHECK(type <> 'test_failure')");
        var ended=task.next(TaskStatus.FAILED,"failed",List.of(),false,1);
        assertThatThrownBy(()->store.saveTask(task,ended,"test_failure")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.task(task.id()).orElseThrow()).isEqualTo(task);
        store.saveTask(task,ended,"task_updated");
        assertThatThrownBy(()->store.saveTask(task,ended,"task_updated")).isInstanceOf(IllegalStateException.class);
        var states=new JdbcAgentStateStore(jdbc,new ObjectMapper());states.initialize();
        states.save(null,"child","agent_state",AgentState.builder().sessionId("child").context(List.of(new UserMessage("user-input"))).build());
        assertThat(new JdbcAgentStateStore(jdbc,new ObjectMapper()).get(null,"child","agent_state",AgentState.class).orElseThrow().getContext().get(0).getTextContent())
                .isEqualTo("user-input");
    }
    @Test void concurrentLateReceiptsAreNotLostAndAuditFailureRollsThemBack()throws Exception{
        var task=task("a");store.createTask(task);
        var op=new OperationRecord("op","c","write","hash",1,"SUBMITTING","");store.startOperation(task,op,1);
        var current=store.task("a").orElseThrow();store.saveTask(current,current.next(TaskStatus.UNKNOWN,"cancelled",List.of(),true,2),"task_cancelled");
        var receipt=new OperationReceipt("r1","test-adapter","SUCCESS","raw",OperationOutcome.CONFIRMED_APPLIED,"provider-order",3);
        jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_receipt CHECK(type <> 'operation_receipt')");
        assertThatThrownBy(()->store.recordReceipt("a","op",receipt,3)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.task("a").orElseThrow().operations().get(0).receipts()).isEmpty();
        jdbc.execute("ALTER TABLE events DROP CONSTRAINT reject_receipt");
        var second=new OperationReceipt("r2","test-adapter","SUCCESS","other raw",OperationOutcome.CONFIRMED_APPLIED,"provider-order",4);
        var pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
        try{
            var jobs=List.of(receipt,second).stream().map(r->pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);store.recordReceipt("a","op",r,4);return true;})).toList();
            for(var job:jobs)job.get(10,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        var updated=store.task("a").orElseThrow();assertThat(updated.status()).isEqualTo(TaskStatus.UNKNOWN);
        assertThat(updated.operations().get(0).receipts()).hasSize(2);assertThat(updated.unresolvedEffects()).isFalse();
        long revision=updated.revision();store.recordReceipt("a","op",receipt,5);
        assertThat(store.task("a").orElseThrow().revision()).isEqualTo(revision);
        store.startOperation(createSecond(),new OperationRecord("other","other","write","h",5,"SUBMITTING",""),5);
    }
    @Test void observationEvidenceSurvivesReloadAndAuditFailureRollsBackInvalidation(){
        var original=task("observation");store.createTask(original);
        var pending=new FactObservation("obs","ride.quote","test-adapter","query","hash",10,"","",null);
        store.recordObservation(original.id(),pending,true,10);
        var current=store.task(original.id()).orElseThrow();
        var returned=pending.returned(current,"raw evidence",new FactExtractor.Value("ride_quote",Map.of("durationSeconds","60"),1000),11);
        store.recordObservation(original.id(),returned,false,11);
        var restored=new JdbcCoordinationStore(jdbc,jdbc.getDataSource(),new ObjectMapper()).task(original.id()).orElseThrow();
        assertThat(TaskFacts.validAt(restored,12)).hasSize(1);assertThat(restored.observations().get(0).output()).isEqualTo("raw evidence");
        jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_observation CHECK(type <> 'fact_observation_started') NOT VALID");
        var refresh=new FactObservation("refresh","ride.quote","test-adapter","query","hash",12,"","",null);
        assertThatThrownBy(()->store.recordObservation(original.id(),refresh,true,12)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.task(original.id()).orElseThrow()).isEqualTo(restored);
        jdbc.execute("ALTER TABLE events DROP CONSTRAINT reject_observation");
        store.recordObservation(original.id(),refresh,true,12);
        assertThat(TaskFacts.of(store.task(original.id()).orElseThrow())).isEmpty();
    }
    @Test void concurrentObservationCompletionsPreserveBothEvidenceAndStartOrder()throws Exception{
        var original=task("observation");store.createTask(original);
        var first=new FactObservation("first","ride.quote","test","query","hash",10,"","",null);
        var second=new FactObservation("second","ride.quote","test","query","hash",11,"","",null);
        store.recordObservation(original.id(),first,true,10);store.recordObservation(original.id(),second,true,11);
        var current=store.task(original.id()).orElseThrow();
        var a=first.returned(current,"earlier quote",new FactExtractor.Value("ride_quote",Map.of("durationSeconds","60"),1000),12);
        var b=second.returned(current,"refresh failed",null,12);
        var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try{
            var jobs=List.of(a,b).stream().map(observation->pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);store.recordObservation(original.id(),observation,false,12);return true;})).toList();
            for(var job:jobs)job.get(10,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        var restored=store.task(original.id()).orElseThrow();
        assertThat(restored.observations()).containsExactly(a,b);assertThat(TaskFacts.of(restored)).isEmpty();
        store.saveTask(restored,restored.next(TaskStatus.CANCELLED,"cancelled",List.of(),false,13),"cancelled");
        assertThatThrownBy(()->store.recordObservation(original.id(),new FactObservation("late","ride.quote","test","query","hash",14,"","",null),true,14)).isInstanceOf(IllegalStateException.class);
    }
    private TaskRecord createSecond(){var next=task("b");store.createTask(next);return next;}
    @Test void unresolvedCredentialScopeBlocksOtherSessionsAndReleasesOnlyWithEvidence(){
        String scope="b".repeat(64);var a=task("a");store.createTask(a);
        var first=new OperationRecord("op-a","a","write","h",1,"SUBMITTING","",OperationOutcome.UNKNOWN,"",List.of(),UUID.randomUUID().toString(),scope);
        store.startOperation(a,first,1);store.createGoal(new GoalRecord("other","other",1,"intent","intent",0,0));
        var b=new TaskRecord("b","other","other",1,new TaskSpec("b","domain","goal",null,null,TaskScope.REQUEST_APPROVAL),"child","v1",TaskStatus.RUNNING,"",null,List.of(),false,List.of(),0,0,0);store.createTask(b);
        var second=new OperationRecord("op-b","b","write","h",1,"SUBMITTING","",OperationOutcome.UNKNOWN,"",List.of(),UUID.randomUUID().toString(),scope);
        assertThatThrownBy(()->store.startOperation(b,second,2)).isInstanceOf(IllegalStateException.class);
        jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_scope_receipt CHECK(type <> 'operation_receipt')");
        assertThatThrownBy(()->store.recordReceipt("a","op-a",new OperationReceipt("p","test","SUCCESS","raw",OperationOutcome.CONFIRMED_APPLIED,"order",3),3)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_claims",Integer.class)).isEqualTo(1);
        jdbc.execute("ALTER TABLE events DROP CONSTRAINT reject_scope_receipt");store.recordReceipt("a","op-a",new OperationReceipt("p","test","SUCCESS","raw",OperationOutcome.CONFIRMED_APPLIED,"order",3),3);
        store.startOperation(b,second,4);assertThat(store.task("b").orElseThrow().effectStarted()).isTrue();
    }
    @Test void simultaneousSessionsCannotBothClaimTheSameCredentialScope()throws Exception{
        var a=task("a");store.createTask(a);store.createGoal(new GoalRecord("other","other",1,"intent","intent",0,0));
        var b=new TaskRecord("b","other","other",1,new TaskSpec("b","domain","goal",null,null,TaskScope.REQUEST_APPROVAL),"child","v1",TaskStatus.RUNNING,"",null,List.of(),false,List.of(),0,0,0);store.createTask(b);
        var wins=new AtomicInteger();var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try{
            var jobs=List.of(a,b).stream().map(t->pool.submit(()->{
                barrier.await(5,TimeUnit.SECONDS);
                try{store.startOperation(t,new OperationRecord("op-"+t.id(),"c","write","h",1,"SUBMITTING","",OperationOutcome.UNKNOWN,"",List.of(),UUID.randomUUID().toString(),"a".repeat(64)),1);wins.incrementAndGet();}
                catch(IllegalStateException rejected){assertThat(rejected).hasMessageContaining("跨会话");}return true;
            })).toList();for(var job:jobs)job.get(10,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        assertThat(wins.get()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_claims",Integer.class)).isEqualTo(1);
    }
    private JdbcGoalBudgetStore budgets(){var budgets=new JdbcGoalBudgetStore(jdbc,jdbc.getDataSource(),new ObjectMapper());budgets.initialize();return budgets;}
    @Test void budgetQuotaSurvivesNewStoreInstancesAndConcurrentReservations()throws Exception{
        var budgets=budgets();var limits=new GoalBudgetStore.Limits(1,2,2,100);budgets.open("g",limits);
        var pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);var wins=new AtomicInteger();
        try{
            var jobs=java.util.stream.IntStream.range(0,2).mapToObj(i->pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);try{budgets.reserve("g",GoalBudgetStore.Kind.MODEL);wins.incrementAndGet();}catch(RunBudget.BudgetExceeded exhausted){}return true;})).toList();
            for(var job:jobs)job.get(10,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        var restored=budgets();var snapshot=restored.open("g",new GoalBudgetStore.Limits(999,999,999,999));
        assertThat(wins.get()).isEqualTo(1);assertThat(snapshot.limits()).isEqualTo(limits);assertThat(snapshot.models()).isEqualTo(1);assertThat(snapshot.pendingUsage()).isEqualTo(1);assertThat(snapshot.usageComplete()).isFalse();
        assertThatThrownBy(()->restored.reserve("g",GoalBudgetStore.Kind.MODEL)).isInstanceOf(RunBudget.BudgetExceeded.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE type='budget_reserved'",Integer.class)).isEqualTo(1);
    }
    @Test void budgetAuditFailureRollsBackBothReservationAndSettlement(){
        var budgets=budgets();budgets.open("g",new GoalBudgetStore.Limits(5,5,5,100));
        jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_budget CHECK(type <> 'budget_reserved')");
        assertThatThrownBy(()->budgets.reserve("g",GoalBudgetStore.Kind.MODEL)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(budgets.find("g").orElseThrow().models()).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_calls",Integer.class)).isZero();
        jdbc.execute("ALTER TABLE events DROP CONSTRAINT reject_budget");String call=budgets.reserve("g",GoalBudgetStore.Kind.MODEL);
        jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_budget CHECK(type <> 'budget_settled')");
        assertThatThrownBy(()->budgets.settle("g",call,7L,true)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(budgets.find("g").orElseThrow().pendingUsage()).isEqualTo(1);assertThat(budgets.find("g").orElseThrow().knownTokens()).isZero();
        jdbc.execute("ALTER TABLE events DROP CONSTRAINT reject_budget");budgets.settle("g",call,7L,true);var settled=budgets.find("g").orElseThrow();
        budgets.settle("g",call,7L,true);assertThat(budgets.find("g").orElseThrow()).isEqualTo(settled);
        assertThatThrownBy(()->budgets.settle("g",call,9L,true)).isInstanceOf(IllegalStateException.class);
    }
    @Test void openingBudgetRequiresAuditAndKeepsLegacyCoverageUnknown(){
        var budgets=budgets();jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_open CHECK(type <> 'budget_opened')");
        assertThatThrownBy(()->budgets.open("g",new GoalBudgetStore.Limits(5,5,5,100),true)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(budgets.find("g")).isEmpty();jdbc.execute("ALTER TABLE events DROP CONSTRAINT reject_open");
        budgets.open("g",new GoalBudgetStore.Limits(5,5,5,100),true);String call=budgets.reserve("g",GoalBudgetStore.Kind.MODEL);budgets.settle("g",call,0L,true);
        assertThat(budgets().find("g").orElseThrow().historyUnknown()).isTrue();assertThat(budgets().find("g").orElseThrow().usageComplete()).isFalse();
    }
    @Test void goalBudgetRejectsForeignCallsCorruptStateAndTokenOverrun(){
        var budgets=budgets();budgets.open("g",new GoalBudgetStore.Limits(5,5,5,5));String call=budgets.reserve("g",GoalBudgetStore.Kind.MODEL);
        store.createGoal(new GoalRecord("other","other",1,"intent","intent",0,0));budgets.open("other",new GoalBudgetStore.Limits(5,5,5,100));
        assertThatThrownBy(()->budgets.settle("other",call,7L,true)).isInstanceOf(IllegalArgumentException.class);
        budgets.settle("g",call,7L,true);assertThat(budgets.find("g").orElseThrow().knownTokens()).isEqualTo(7);
        assertThatThrownBy(()->budgets.reserve("g",GoalBudgetStore.Kind.TOOL)).isInstanceOf(RunBudget.BudgetExceeded.class);
        jdbc.update("UPDATE goal_budgets SET content='{}' WHERE goal_id='g'");
        assertThatThrownBy(()->budgets.reserve("g",GoalBudgetStore.Kind.MODEL)).isInstanceOf(IllegalStateException.class);
    }
    @Test void changedBoundFactIsRejectedInsideTheSubmissionTransaction(){
        var parent=task("parent");store.createTask(parent);
        store.startOperation(parent,new OperationRecord("op","c","write","h",1,"SUBMITTING",""),1);
        store.recordReceipt(parent.id(),"op",new OperationReceipt("r1","trusted-test","SUCCESS","raw",OperationOutcome.CONFIRMED_APPLIED,"resource",2),2);
        parent=store.task(parent.id()).orElseThrow();var completed=parent.next(TaskStatus.COMPLETED,"done",List.of(),true,3);store.saveTask(parent,completed,"task_updated");
        var fact=TaskFacts.of(completed).get(0);
        var spec=new TaskSpec("consumer","domain","consume",null,List.of(parent.id()),TaskScope.REQUEST_APPROVAL,List.of(new InputBinding(parent.id(),fact.id(),fact.version(),"resource")));
        var consumer=new TaskRecord("consumer","root","g",1,spec,"child","v1",TaskStatus.RUNNING,"",null,List.of(),false,List.of(),0,0,0);store.createTask(consumer);
        store.recordReceipt(parent.id(),"op",new OperationReceipt("r2","trusted-test","SUCCESS","conflicting",OperationOutcome.CONFIRMED_APPLIED,"different",4),4);
        assertThatThrownBy(()->store.startOperation(consumer,new OperationRecord("consumer-op","c","write","h",5,"SUBMITTING",""),5)).isInstanceOf(TaskFacts.InputUnavailable.class);
        assertThat(store.task(consumer.id()).orElseThrow().effectStarted()).isFalse();
    }
    @Test void scopedAnswerAndAuditCommitTogetherAndOnlyOneConcurrentAnswerWins()throws Exception{
        var initial=task("q");store.createTask(initial);var question=initial.next(TaskStatus.NEEDS_INPUT,"地点？",List.of(),false,1);store.saveTask(initial,question,"task_updated");
        var goal=store.goal("root").orElseThrow();var first=question.answered(goal,question.questionId(),"上海",2);
        jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_answer CHECK(type <> 'task_answer_received')");
        assertThatThrownBy(()->store.saveTask(question,first,"task_answer_received")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.task(question.id()).orElseThrow()).isEqualTo(question);
        jdbc.execute("ALTER TABLE events DROP CONSTRAINT reject_answer");
        var second=question.answered(goal,question.questionId(),"北京",2);var wins=new AtomicInteger();var pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
        try{
            var jobs=List.of(first,second).stream().map(answer->pool.submit(()->{
                barrier.await(5,TimeUnit.SECONDS);
                try{store.saveTask(question,answer,"task_answer_received");wins.incrementAndGet();}catch(IllegalStateException conflict){assertThat(conflict).hasMessageContaining("修改");}
                return true;
            })).toList();for(var job:jobs)job.get(10,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        var reloaded=new JdbcCoordinationStore(jdbc,jdbc.getDataSource(),new ObjectMapper()).task(question.id()).orElseThrow();
        assertThat(wins.get()).isEqualTo(1);assertThat(reloaded.status()).isEqualTo(TaskStatus.INPUT_READY);assertThat(reloaded.answers()).hasSize(1);
        assertThat(store.goal("root").orElseThrow()).isEqualTo(goal);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE type='task_answer_received'",Integer.class)).isEqualTo(1);
    }
}
