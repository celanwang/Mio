package org.celanwang.mio.memory;

import org.celanwang.mio.memory.jobs.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MIO_TEST_PG_URL",matches="jdbc:postgresql:.*")
class JdbcMemoryUsageIntegrationTest {
    MemoryJobsDatabase db;
    @BeforeEach void initialize(){db=new MemoryJobsDatabase();}
    @AfterEach void cleanup(){if(db!=null)db.close();}
    @Test void expiredReservationsStayUnknownUntilALateCorrelatedReceiptAndNeverRefundCalls(){
        var call=db.usage.begin(MemoryCallBudget.Kind.MODEL,12,null);db.jdbc.update("UPDATE memory_usage_calls SET expires_at=0");db.usage.recoverExpired();
        assertThat(db.jdbc.queryForObject("SELECT unknown FROM memory_usage_days",Integer.class)).isEqualTo(1);assertThat(db.jdbc.queryForObject("SELECT pending FROM memory_usage_days",Integer.class)).isZero();
        call.settle(17L,true);call.settle(17L,true);
        assertThat(db.jdbc.queryForObject("SELECT known_tokens FROM memory_usage_days",Long.class)).isEqualTo(17);assertThat(db.jdbc.queryForObject("SELECT unknown FROM memory_usage_days",Integer.class)).isZero();
        assertThat(db.jdbc.queryForObject("SELECT calls FROM memory_usage_days",Integer.class)).isEqualTo(1);assertThatThrownBy(()->call.settle(2L,true)).isInstanceOf(IllegalStateException.class);
    }
    @Test void concurrentQuotaReservationsCannotOverspendAndRestartDoesNotResetTheDay() throws Exception {
        var limits=new JdbcMemoryUsage.Limits(1,1000,1000);var usage=new JdbcMemoryUsage(db.jdbc,db.source,limits);var barrier=new CyclicBarrier(2);var paid=new AtomicInteger();var pool=Executors.newFixedThreadPool(2);
        try{var futures=List.of(1,2).stream().map(i->pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);try{var call=usage.begin(MemoryCallBudget.Kind.EMBEDDING,10,null);paid.incrementAndGet();call.settle(5L,true);}catch(MemoryCallBudget.Exhausted expected){}return true;})).toList();for(var future:futures)future.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertThat(paid.get()).isEqualTo(1);assertThatThrownBy(()->new JdbcMemoryUsage(db.jdbc,db.source,limits).begin(MemoryCallBudget.Kind.MODEL,1,null)).isInstanceOf(MemoryCallBudget.Exhausted.class);
        assertThat(usage.nextReset()).isGreaterThan(db.jobs.now());
    }
    @Test void inputAndReportedTokensAreSeparateLimitsAndUnknownUsageIsNotZero(){
        var usage=new JdbcMemoryUsage(db.jdbc,db.source,new JdbcMemoryUsage.Limits(10,20,5));
        assertThatThrownBy(()->usage.begin(MemoryCallBudget.Kind.MODEL,21,null)).isInstanceOf(MemoryCallBudget.InputTooLarge.class);
        var first=usage.begin(MemoryCallBudget.Kind.MODEL,10,null);first.settle(null,false);
        assertThat(db.jdbc.queryForObject("SELECT unknown FROM memory_usage_days",Integer.class)).isEqualTo(1);
        usage.begin(MemoryCallBudget.Kind.EMBEDDING,10,null).settle(7L,true);
        assertThat(db.jdbc.queryForObject("SELECT known_tokens FROM memory_usage_days",Long.class)).isEqualTo(7);assertThatThrownBy(()->usage.begin(MemoryCallBudget.Kind.MODEL,0,null)).isInstanceOf(MemoryCallBudget.Exhausted.class);
    }
    @Test void obsoleteJobCannotStartANewProviderCall(){
        db.append("one",false,true);var old=db.jobs.claim(Set.of(MemoryJob.Kind.DISTILL)).orElseThrow();db.expire(old.id());db.jobs.claim(Set.of(MemoryJob.Kind.DISTILL)).orElseThrow();
        assertThatThrownBy(()->db.usage.begin(MemoryCallBudget.Kind.MODEL,10,old)).isInstanceOf(MemoryJob.LostClaim.class);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM memory_usage_calls",Integer.class)).isZero();
    }
}
