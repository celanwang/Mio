package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.celanwang.mio.controller.MemoryJobsController;
import org.celanwang.mio.memory.jobs.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MemoryBackgroundBoundaryTest {
    @Test void transientQueueIsBoundedNeverRunsInCallerAndRestoresBillingContext() throws Exception {
        var budget=mock(MemoryCallBudget.class);var executor=new LocalMemoryTaskExecutor(budget,1,true);var started=new CountDownLatch(1);var release=new CountDownLatch(1);var drained=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        try{
            executor.execute(()->{try{assertThat(MemoryWorkContext.active()).isTrue();started.countDown();release.await(5,TimeUnit.SECONDS);}catch(Throwable error){failure.set(error);}});
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();executor.execute(drained::countDown);executor.execute(()->{throw new AssertionError("rejected work must not run");});
            assertThat(executor.diagnostics().get("rejected")).isEqualTo(1L);assertThat(executor.diagnostics().get("queued")).isEqualTo(1);assertThat(MemoryWorkContext.active()).isFalse();
            release.countDown();assertThat(drained.await(5,TimeUnit.SECONDS)).isTrue();assertThat(failure.get()).isNull();
        }finally{release.countDown();executor.shutdown();}
    }
    @Test void disabledBackgroundExecutorDoesNotRunOrReserveAnything(){
        var budget=mock(MemoryCallBudget.class);var executor=new LocalMemoryTaskExecutor(budget,1,false);
        try{executor.execute(()->{throw new AssertionError("disabled");});assertThat(executor.diagnostics().get("rejected")).isEqualTo(1L);verifyNoInteractions(budget);}finally{executor.shutdown();}
    }
    @Test void actualEmbeddingClientReservesBeforeIoAndRecordsNativeUsage() throws Exception {
        var requests=new AtomicInteger();var body=new AtomicReference<>("{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":[1,0]}]},\"usage\":{\"total_tokens\":11}}");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{requests.incrementAndGet();byte[] data=body.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);exchange.close();});server.start();
        var budget=mock(MemoryCallBudget.class);var call=mock(MemoryCallBudget.Call.class);when(budget.begin(any(),anyInt(),any())).thenReturn(call);
        var client=new EmbeddingClient("synthetic-key","http://127.0.0.1:"+server.getAddress().getPort(),"fixture","",0,new ObjectMapper());
        try{
            try(var context=MemoryWorkContext.open(budget,null)){assertThat(client.embed(List.of("abc"))).isPresent();}
            verify(budget).begin(MemoryCallBudget.Kind.EMBEDDING,3,null);verify(call).settle(11L,true);assertThat(requests.get()).isEqualTo(1);
            doThrow(new MemoryCallBudget.Exhausted()).when(budget).begin(any(),anyInt(),any());
            try(var context=MemoryWorkContext.open(budget,null)){assertThatThrownBy(()->client.embed(List.of("abc"))).isInstanceOf(MemoryCallBudget.Exhausted.class);}
            assertThat(requests.get()).isEqualTo(1);
        }finally{server.stop(0);}
    }
    @Test void diagnosticsNeverExposeCheckpointOrMessageContentsAndStorageErrorsAreNotZeroUsage() throws Exception {
        var jobs=mock(JdbcMemoryJobs.class);var usage=mock(JdbcMemoryUsage.class);var worker=mock(MemoryJobDispatcher.class);var cache=mock(LocalMemoryTaskExecutor.class);
        var job=new MemoryJob("DISTILL:hash","hash",MemoryJob.Kind.DISTILL,1,MemoryJob.State.RETRY,1,1,null,0,1,"private-model-result","PROCESSING_FAILED",1,2);
        when(jobs.diagnostics()).thenReturn(Map.of("recent",List.of(job.view())));when(usage.diagnostics()).thenReturn(Map.of("timezone","UTC"));when(worker.diagnostics()).thenReturn(Map.of());when(cache.diagnostics()).thenReturn(Map.of());
        var mvc=MockMvcBuilders.standaloneSetup(new MemoryJobsController(jobs,usage,worker,cache)).build();
        var result=mvc.perform(get("/api/memory/jobs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(result).contains("resultSaved").doesNotContain("private-model-result","messageId");
        when(jobs.diagnostics()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private database details"));
        var failed=mvc.perform(get("/api/memory/jobs")).andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();assertThat(failed).doesNotContain("private database details");
        mvc.perform(post("/api/memory/jobs")).andExpect(status().isMethodNotAllowed());
    }
}
