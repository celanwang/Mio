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
    private TaskRecord createSecond(){var next=task("b");store.createTask(next);return next;}
}
