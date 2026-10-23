package org.celanwang.mio.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.memory.*;
import org.celanwang.mio.memory.jobs.*;
import org.celanwang.mio.orchestration.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MIO_TEST_PG_URL",matches="jdbc:postgresql:.*")
class JdbcMaintenanceIntegrationTest {
    final ObjectMapper json=new ObjectMapper();
    String schema,url,user,password; JdbcTemplate admin; Fixture db;
    class Fixture {
        final DriverManagerDataSource source; final JdbcTemplate jdbc;
        final JdbcCoordinationStore store; final JdbcExecutionLeaseStore leases; final JdbcTaskQueue queue;
        final JdbcMemoryJobs jobs; final JdbcMemoryUsage usage; final JdbcGoalBudgetStore budgets;
        final JdbcMaintenance maintenance;
        Fixture(String databaseUrl,boolean initialize) {
            source=new DriverManagerDataSource(databaseUrl+(databaseUrl.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
            jdbc=new JdbcTemplate(source);
            store=new JdbcCoordinationStore(jdbc,source,json); leases=new JdbcExecutionLeaseStore(jdbc,source);
            queue=new JdbcTaskQueue(jdbc,source,json,store,leases); budgets=new JdbcGoalBudgetStore(jdbc,source,json);
            jobs=new JdbcMemoryJobs(jdbc,source,3,120000,1);
            usage=new JdbcMemoryUsage(jdbc,source,new JdbcMemoryUsage.Limits(100,10000,10000));
            maintenance=new JdbcMaintenance(jdbc,source,json);
            if(initialize) {
                jdbc.execute("CREATE TABLE events(id TEXT PRIMARY KEY,ts BIGINT NOT NULL,session_id TEXT,node TEXT,type TEXT,title TEXT,detail TEXT)");
                jdbc.execute("CREATE TABLE messages(id TEXT PRIMARY KEY,session_id TEXT,ts BIGINT NOT NULL,role TEXT NOT NULL,text TEXT NOT NULL)");
                jdbc.execute("CREATE TABLE embeddings(id TEXT PRIMARY KEY,embedding public.vector(1024) NOT NULL)");
                store.initialize(); leases.initialize(); queue.initialize(); budgets.initialize(); jobs.initialize(); usage.initialize(); ProfileStore.initializeSchema(jdbc);
            }
        }
        void seed() {
            store.createGoal(new GoalRecord("g","session",1,"真实用户意图","真实用户意图",1,1));
            budgets.open("g",new GoalBudgetStore.Limits(10,10,10,10000));
            budgets.reserve("g",GoalBudgetStore.Kind.MODEL);
            var known=budgets.reserve("g",GoalBudgetStore.Kind.MODEL); budgets.settle("g",known,7L,true);
            jobs.append(new EpisodeStore.Episode("message","session",1,"user","private original text"),false,true);
            jdbc.execute("INSERT INTO embeddings VALUES ('message',array_fill(0.1::real,ARRAY[1024])::public.vector)");
            usage.begin(MemoryCallBudget.Kind.MODEL,21,null);
            var call=usage.begin(MemoryCallBudget.Kind.MODEL,19,null); call.settle(9L,true);
            var profiles=new ProfileStore(jdbc);
            profiles.savePage(new ProfilePage("manual","manual","global","explicit",1,1,"explicit preference")); profiles.deletePage("deleted");
        }
        TaskRecord task(String id,TaskStatus status,ExecutionLeaseStore.Token token) {
            var approvals=status==TaskStatus.AWAITING_APPROVAL?List.of(new PendingApproval("a","call","write","confirm",Map.of(),"h","c",Long.MAX_VALUE,null)):List.<PendingApproval>of();
            var task=new TaskRecord(id,"session","g",1,new TaskSpec(id,"domain","prepare",null,null,TaskScope.REQUEST_APPROVAL),"child"+id,"v1",status,"",null,approvals,false,List.of(),0,1,1,List.of(),List.of(),null,token);
            store.createTask(task);return task;
        }
    }
    @BeforeEach void initialize() {
        url=System.getenv("MIO_TEST_PG_URL");user=System.getenv().getOrDefault("MIO_TEST_PG_USERNAME","postgres");password=System.getenv().getOrDefault("MIO_TEST_PG_PASSWORD","");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password)); schema="mio_maintenance_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE EXTENSION IF NOT EXISTS vector");admin.execute("CREATE SCHEMA "+schema); db=new Fixture(url,true); db.seed();
    }
    @AfterEach void cleanup() { if(admin!=null) admin.execute("DROP SCHEMA "+schema+" CASCADE"); }
    JdbcMaintenance.Plan preview(JdbcMaintenance.Operation operation,String target) {return db.maintenance.preview(operation,target);}
    String apply(JdbcMaintenance.Plan plan) { return db.maintenance.apply(plan.operation(),plan.target(),plan.digest(),"synthetic maintenance test",true); }

    @Test void retryIsReadOnlyUntilExplicitApplyAndKeepsCheckpointOriginalAndBudgets() throws Exception {
        var old=db.jobs.claim(Set.of(MemoryJob.Kind.DISTILL)).orElseThrow();db.jobs.checkpoint(old,"{\"schemaVersion\":1,\"saved\":true}");
        db.jdbc.update("UPDATE memory_jobs SET state='DEAD',owner=NULL,attempts=5 WHERE id=?",old.id());
        var before=db.jobs.find(old.id()).orElseThrow(); var budget=db.budgets.find("g").orElseThrow();
        var plan=preview(JdbcMaintenance.Operation.RETRY_MEMORY_JOB,old.id());
        assertThat(db.jobs.find(old.id()).orElseThrow()).isEqualTo(before);
        assertThat(json.writeValueAsString(plan)).doesNotContain("private original", "schemaVersion\\\"", "saved\\\"");
        assertThatThrownBy(()->db.maintenance.apply(plan.operation(),plan.target(),plan.digest(),"test",false)).isInstanceOf(IllegalArgumentException.class);
        String audit=apply(plan); assertThat(audit).startsWith("maintenance:");
        var next=db.jobs.find(old.id()).orElseThrow();assertThat(next.state()).isEqualTo(MemoryJob.State.RETRY);
        assertThat(next.result()).isEqualTo(before.result());assertThat(next.attempts()).isZero();assertThat(next.generation()).isGreaterThan(before.generation());
        assertThat(db.budgets.find("g").orElseThrow()).isEqualTo(budget);
        assertThat(db.jdbc.queryForObject("SELECT text FROM messages",String.class)).isEqualTo("private original text");
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM events WHERE id=?",Long.class,audit)).isEqualTo(1);
        assertThatThrownBy(()->db.jobs.checkpoint(old,"late")).isInstanceOf(MemoryJob.LostClaim.class);
        assertThat(db.jobs.claim(Set.of(MemoryJob.Kind.DISTILL)).orElseThrow().result()).isEqualTo(before.result());
    }
    @Test void stalePreviewAndAuditFailureDoNotPartiallyRetry() {
        db.jdbc.update("UPDATE memory_jobs SET state='DEAD'");var plan=preview(JdbcMaintenance.Operation.RETRY_MEMORY_JOB,"DISTILL:message");
        db.jdbc.update("UPDATE memory_jobs SET generation=generation+1");
        assertThatThrownBy(()->apply(plan)).isInstanceOf(IllegalStateException.class);
        var fresh=preview(plan.operation(),plan.target());
        db.jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_maintenance CHECK(type<>'maintenance_applied')");
        assertThatThrownBy(()->apply(fresh)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.jobs.find(plan.target()).orElseThrow().state()).isEqualTo(MemoryJob.State.DEAD);
        assertThat(preview(plan.operation(),plan.target()).digest()).isEqualTo(fresh.digest());
    }
    @Test void competingRetriesOnlyApplyOnePreview() throws Exception {
        db.jdbc.update("UPDATE memory_jobs SET state='DEAD'"); var plan=preview(JdbcMaintenance.Operation.RETRY_MEMORY_JOB,"DISTILL:message");
        var pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
        try {
            var futures=List.of(1,2).stream().map(i->pool.submit(()->{barrier.await();try{apply(plan);return true;}catch(IllegalStateException expected){return false;}})).toList();
            int winners=0;for(var future:futures) if(future.get(10,TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    @Test void restoreAuditFailureRollsBackRevocationsTaskChangesAndBothUsageLedgers() {
        var lease=db.leases.acquire("session",120000);db.task("running",TaskStatus.RUNNING,lease);db.task("approval",TaskStatus.AWAITING_APPROVAL,null);
        var claim=db.jobs.claim(Set.of(MemoryJob.Kind.DISTILL)).orElseThrow();
        var plan=preview(JdbcMaintenance.Operation.RECOVER_RESTORED_RUNTIME,"");
        db.jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_maintenance CHECK(type<>'maintenance_applied')");
        assertThatThrownBy(()->apply(plan)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.leases.valid(lease)).isTrue();assertThat(db.store.task("running").orElseThrow().status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(db.store.task("approval").orElseThrow().approvals()).hasSize(1);assertThat(db.jobs.find(claim.id()).orElseThrow().claim()).isEqualTo(claim);
        assertThat(db.budgets.find("g").orElseThrow().pendingUsage()).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT pending FROM memory_usage_days",Integer.class)).isEqualTo(1);
        assertThat(preview(plan.operation(),plan.target()).digest()).isEqualTo(plan.digest());
    }

    @Test
    @EnabledIfEnvironmentVariable(named="MIO_TEST_PG_CONTAINER",matches="[a-f0-9]{12,64}")
    void realDumpRestoreRevokesFutureOwnersAndPreservesUnknownEffectsAndMemory() throws Exception {
        String container=System.getenv("MIO_TEST_PG_CONTAINER");
        // Only the disposable, labelled, loopback container owned by eval-postgres.sh is eligible.
        assertThat(command("docker","inspect","--format","{{index .Config.Labels \"mio.evaluation\"}}",container)).isEqualTo("true");
        String binding=command("docker","port",container,"5432");assertThat(url).isEqualTo("jdbc:postgresql://"+binding+"/postgres");assertThat(binding).startsWith("127.0.0.1:");
        var lease=db.leases.acquire("session",120000);var running=db.task("running",TaskStatus.RUNNING,lease);db.task("approval",TaskStatus.AWAITING_APPROVAL,null);
        var op=new OperationRecord("op","call","write","hash",1,"SUBMITTING","",OperationOutcome.UNKNOWN,"",List.of(),UUID.randomUUID().toString(),"a".repeat(64));
        db.store.startOperation(running,op,1);
        var queued=new TaskRecord("queued","session","g",1,new TaskSpec("queued","domain","prepare",null,null,null,null,"default",TaskSpec.Dispatch.QUEUED),"child-q","v1",TaskStatus.QUEUED,"",null,List.of(),false,List.of(),0,1,1);
        db.queue.enqueue(queued);db.queue.claim(queued.id(),lease).orElseThrow();
        var claim=db.jobs.claim(Set.of(MemoryJob.Kind.DISTILL)).orElseThrow();db.jobs.checkpoint(claim,"saved-model-result");
        String profile=db.jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(p) ORDER BY id)::text FROM profiles p",String.class);
        String vector=db.jdbc.queryForObject("SELECT embedding::text FROM embeddings WHERE id='message'",String.class);
        var originalPlan=preview(JdbcMaintenance.Operation.RECOVER_RESTORED_RUNTIME,"");
        Path dump=Files.createTempFile("mio-recovery-",".dump");String database="mio_restore_"+UUID.randomUUID().toString().replace("-","");
        try {
            run(new ProcessBuilder("docker","exec",container,"pg_dump","-U","postgres","-Fc","-n",schema,"postgres").redirectOutput(dump.toFile()));
            admin.execute("CREATE DATABASE "+database);
            new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://"+binding+"/"+database,user,password)).execute("CREATE EXTENSION vector");
            run(new ProcessBuilder("docker","exec","-i",container,"pg_restore","-U","postgres","--exit-on-error","-d",database).redirectInput(dump.toFile()));
            Fixture restored=new Fixture("jdbc:postgresql://"+binding+"/"+database,false);
            assertThat(restored.leases.valid(lease)).isTrue(); // A normal expiry wait would incorrectly trust this owner.
            assertThat(restored.jobs.find(claim.id()).orElseThrow().result()).isEqualTo("saved-model-result");
            assertThatThrownBy(()->restored.maintenance.apply(originalPlan.operation(),"",originalPlan.digest(),"test",true)).isInstanceOf(IllegalStateException.class);
            var plan=restored.maintenance.preview(originalPlan.operation(),"");
            restored.maintenance.apply(plan.operation(),"",plan.digest(),"synthetic dump/restore drill",true);
            assertThat(restored.leases.valid(lease)).isFalse();assertThat(restored.store.goal("session").orElseThrow().state()).isEqualTo(GoalRecord.State.PAUSED);
            assertThat(restored.store.goal("session").orElseThrow().trustedIntent()).isEqualTo("真实用户意图");
            assertThat(restored.store.task("running").orElseThrow().status()).isEqualTo(TaskStatus.UNKNOWN);
            assertThat(restored.store.task("running").orElseThrow().operations()).containsExactly(op);
            assertThat(restored.store.task("approval").orElseThrow().approvals()).isEmpty();assertThat(restored.store.task("approval").orElseThrow().status()).isEqualTo(TaskStatus.STALE);
            assertThat(restored.jdbc.queryForObject("SELECT count(*) FROM operation_claims",Long.class)).isEqualTo(1);
            assertThat(restored.queue.find("queued").orElseThrow().state()).isEqualTo(TaskQueue.State.READY);
            assertThatThrownBy(()->restored.queue.claim("queued",lease)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->restored.jobs.checkpoint(claim,"late")).isInstanceOf(MemoryJob.LostClaim.class);
            var next=restored.jobs.claim(Set.of(MemoryJob.Kind.DISTILL)).orElseThrow();assertThat(next.result()).isEqualTo("saved-model-result");assertThat(next.attempt()).isEqualTo(claim.attempt()+1);
            var budget=restored.budgets.find("g").orElseThrow();assertThat(budget.models()).isEqualTo(2);assertThat(budget.knownTokens()).isEqualTo(7);assertThat(budget.pendingUsage()).isZero();assertThat(budget.unknownUsage()).isEqualTo(1);
            var memory=restored.jdbc.queryForMap("SELECT * FROM memory_usage_days");assertThat(memory).containsEntry("calls",2).containsEntry("input_chars",40L).containsEntry("known_tokens",9L).containsEntry("pending",0).containsEntry("unknown",1);
            assertThat(restored.jdbc.queryForObject("SELECT text FROM messages",String.class)).isEqualTo("private original text");
            assertThat(restored.jdbc.queryForObject("SELECT embedding::text FROM embeddings WHERE id='message'",String.class)).isEqualTo(vector);
            assertThat(restored.jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(p) ORDER BY id)::text FROM profiles p",String.class)).isEqualTo(profile);
            var again=restored.maintenance.preview(plan.operation(),"");assertThat(again.affected().get("goal_pending_usage")).isZero();assertThat(again.affected().get("memory_pending_usage")).isZero();
        } finally { Files.deleteIfExists(dump);admin.execute("DROP DATABASE IF EXISTS "+database+" WITH (FORCE)"); }
    }
    @Test void unbackedPendingUsageFailsClosedInsteadOfDeclaringRecoveryComplete() {
        var lease=db.leases.acquire("session",120000);
        db.jdbc.update("UPDATE memory_usage_days SET pending=pending+1");
        var plan=preview(JdbcMaintenance.Operation.RECOVER_RESTORED_RUNTIME,"");
        assertThatThrownBy(()->apply(plan)).isInstanceOf(IllegalStateException.class);
        assertThat(db.leases.valid(lease)).isTrue();assertThat(db.store.goal("session").orElseThrow().state()).isEqualTo(GoalRecord.State.ACTIVE);
        assertThat(db.budgets.find("g").orElseThrow().pendingUsage()).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT pending FROM memory_usage_days",Integer.class)).isEqualTo(2);
    }
    static String command(String...args) throws Exception {
        Path output=Files.createTempFile("mio-command-",".out");
        try {run(new ProcessBuilder(args).redirectOutput(output.toFile()));return Files.readString(output).strip();}finally{Files.deleteIfExists(output);}
    }
    static void run(ProcessBuilder builder) throws Exception {
        Path error=Files.createTempFile("mio-command-",".err");
        try {
            var process=builder.redirectError(error.toFile()).start();
            if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("fixture command timed out");}
            assertThat(process.exitValue()).as("fixture command must succeed").isZero();
        }finally{Files.deleteIfExists(error);}
    }
}
