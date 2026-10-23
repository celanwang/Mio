package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import reactor.core.publisher.Flux;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfEnvironmentVariable(named="MIO_TEST_PG_URL", matches="jdbc:postgresql:.*")
class JdbcMemoryConsistencyIntegrationTest {
    JdbcTemplate admin, jdbc;String schema;ProfileStore profiles;PgEpisodeStore episodes;org.celanwang.mio.memory.jobs.JdbcMemoryJobs jobs;
    @BeforeEach void initialize() {
        var base = new DriverManagerDataSource(System.getenv("MIO_TEST_PG_URL"), System.getenv().getOrDefault("MIO_TEST_PG_USERNAME", "postgres"), System.getenv().getOrDefault("MIO_TEST_PG_PASSWORD", ""));
        admin = new JdbcTemplate(base);schema = "mio_memory_" + UUID.randomUUID().toString().replace("-", "");admin.execute("CREATE SCHEMA " + schema);
        var source = new AbstractDataSource() {
            private Connection scope(Connection c) throws SQLException {try (var s=c.createStatement()) {s.execute("SET search_path TO " + schema);return c;} catch(SQLException failure) {c.close();throw failure;}}
            public Connection getConnection() throws SQLException {return scope(base.getConnection());}
            public Connection getConnection(String u,String p) throws SQLException {return scope(base.getConnection(u,p));}
        };
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE messages(id TEXT PRIMARY KEY, session_id TEXT, ts BIGINT NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL)");
        // Start from the old schema to exercise the non-destructive upgrade path.
        jdbc.execute("CREATE TABLE profiles(id TEXT PRIMARY KEY, scope TEXT NOT NULL, title TEXT NOT NULL, source TEXT NOT NULL, confidence DOUBLE PRECISION NOT NULL, content TEXT NOT NULL, source_messages TEXT[], created_at BIGINT, updated_at BIGINT NOT NULL)");
        jdbc.update("INSERT INTO profiles(id,scope,title,source,confidence,content,updated_at) VALUES ('legacy','global','legacy','explicit',1,'保留原文',1)");
        ProfileStore.initializeSchema(jdbc);ProfileStore.initializeSchema(jdbc);
        profiles = new ProfileStore(jdbc);jobs=new org.celanwang.mio.memory.jobs.JdbcMemoryJobs(jdbc,source,3,120000,1000);jobs.initialize();episodes = new PgEpisodeStore(jdbc,jobs,false,false);
    }
    @AfterEach void cleanup() {if (admin!=null && schema!=null) admin.execute("DROP SCHEMA " + schema + " CASCADE");}
    ProfilePage page(String id,String source,String text) {return new ProfilePage(id,id,"global",source,1,System.currentTimeMillis(),text);}

    @Test void upgradesPreserveLegacyRowsAndExplicitPagesCannotBeOverwrittenByInference() {
        var snapshot=profiles.snapshot();assertThat(snapshot.revisions().get("legacy")).isZero();
        assertThat(profiles.saveInferred(page("legacy","inferred","覆盖"),0L)).isFalse();
        assertThat(profiles.pagesStrict().get(0).content()).isEqualTo("保留原文");
        assertThat(profiles.saveInferred(page("new","inferred","旧"),null)).isTrue();
        profiles.savePage(page("new","explicit","人工编辑"));
        assertThat(profiles.saveInferred(page("new","inferred","过期覆盖"),1L)).isFalse();
        assertThat(profiles.saveInferred(page("new","inferred","来源降级"),2L)).isFalse();
    }
    @Test void deletedPagesAndAbsentPageTombstonesCannotBeResurrectedExceptByExplicitEditing() {
        profiles.saveInferred(page("food","inferred","旧"),null);profiles.deletePage("food");profiles.deletePage("absent");
        assertThat(profiles.saveInferred(page("food","inferred","复活"),1L)).isFalse();
        assertThat(profiles.saveInferred(page("food","inferred","复活"),null)).isFalse();
        assertThat(profiles.saveInferred(page("absent","inferred","复活"),null)).isFalse();
        assertThat(profiles.pagesStrict()).extracting(ProfilePage::id).doesNotContain("food","absent");
        assertThat(jdbc.queryForObject("SELECT content FROM profiles WHERE id='food'",String.class)).isEmpty();
        profiles.savePage(page("food","explicit","重新创建"));
        assertThat(profiles.pagesStrict()).extracting(ProfilePage::content).contains("重新创建");
    }
    @Test void independentWritersCannotBothApplyTheSameInferredVersion() throws Exception {
        profiles.saveInferred(page("food","inferred","初始"),null);
        var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try {
            var jobs=List.of("A","B").stream().map(value->pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);return new ProfileStore(jdbc).saveInferred(page("food","inferred",value),1L);})).toList();
            var outcomes=new ArrayList<Boolean>();for(var job:jobs)outcomes.add(job.get(10,TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder(true,false);
            assertThat(profiles.snapshot().revisions().get("food")).isEqualTo(2);
        } finally {pool.shutdownNow();}
    }
    @Test void actualDistillerUsesItsOriginalSnapshotWhenManualEditOrDeletionOccursDuringModelCall() {
        for(boolean delete:List.of(false,true)) {
            String id=delete?"deleted":"edited";profiles.saveInferred(page(id,"inferred","原文"),null);
            var model=mock(Model.class);
            when(model.stream(anyList(),any(),any())).thenReturn(Flux.defer(()->{
                if(delete)profiles.deletePage(id);else profiles.savePage(page(id,"explicit","人工内容"));
                return Flux.just(io.agentscope.core.model.ChatResponse.builder().content(List.of(TextBlock.builder()
                        .text("{\"pages\":[{\"slug\":\""+id+"\",\"title\":\"页面\",\"content\":\"旧模型结果\"}],\"triples\":[]}").build())).build());
            }));
            var claim=new org.celanwang.mio.memory.jobs.MemoryJob.Claim("job-"+id,id,org.celanwang.mio.memory.jobs.MemoryJob.Kind.DISTILL,1,1,1,"owner",Long.MAX_VALUE,null);
            org.celanwang.mio.memory.jobs.MemoryCallBudget budget=(kind,chars,owner)->(tokens,complete)->{};
            try(var context=org.celanwang.mio.memory.jobs.MemoryWorkContext.open(budget,claim)) {
                var distiller=new ProfileDistiller(model,profiles,new PgEntityStore(jdbc),new ObjectMapper(),true);
                try {var plan=distiller.prepare(new EpisodeStore.Episode(id,"s",1,"user","更新偏好"),claim);distiller.apply(plan,claim);}catch(Exception error){throw new AssertionError(error);}
            }
            assertThat(profiles.pagesStrict()).extracting(ProfilePage::content).doesNotContain("旧模型结果");
            if(delete)assertThat(profiles.pagesStrict()).extracting(ProfilePage::id).doesNotContain(id);
            else assertThat(profiles.pagesStrict()).filteredOn(p->p.id().equals(id)).extracting(ProfilePage::content).containsExactly("人工内容");
        }
    }
    @Test void databaseDeduplicatesAcrossInstancesAndRejectsConflictingOriginals() throws Exception {
        var episode=new EpisodeStore.Episode("message","s",1,"user","原文");
        var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try {
            var submissions=List.of(1,2).stream().map(i->pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);return new PgEpisodeStore(jdbc,jobs,false,false).append(episode);})).toList();
            var results=new ArrayList<EpisodeStore.AppendResult>();for(var job:submissions)results.add(job.get(10,TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(EpisodeStore.AppendResult.INSERTED,EpisodeStore.AppendResult.EXISTING);
        } finally {pool.shutdownNow();}
        assertThat(episodes.append(new EpisodeStore.Episode("message","s",2,"user","被改写"))).isEqualTo(EpisodeStore.AppendResult.FAILED);
        assertThat(episodes.listAll()).containsExactly(episode);
        jdbc.execute("ALTER TABLE messages ADD CONSTRAINT reject_new CHECK(id='message')");
        assertThat(episodes.append(new EpisodeStore.Episode("new","s",3,"user","写失败"))).isEqualTo(EpisodeStore.AppendResult.FAILED);
    }
}
