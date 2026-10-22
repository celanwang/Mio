package org.celanwang.mio.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.config.TencentRideProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CorrelatedReceiptLookupTest {
    @TempDir Path directory;
    @Test void missingReceiptNeverQueriesProviderAndExistingReceiptKeepsCorrelation()throws Exception{
        var props=new TencentRideProperties();props.setEnabled(true);props.setToken("synthetic-token");props.setDataDirectory(directory);
        var calls=new AtomicInteger();var json=new ObjectMapper();
        var service=new TencentRideService(props,json,"skills"){
            @Override public synchronized String execute(String session,Map<String,Object> input){assertThat(input.get("command")).isEqualTo("query-ongoing-order");calls.incrementAndGet();return "unrelated current order";}
        };
        String logical=UUID.randomUUID().toString(),scope=service.operationScope();
        assertThat(json.readTree(service.reconcile("child",logical,scope)).path("status").asText()).isEqualTo("UNKNOWN");assertThat(calls.get()).isZero();
        String account=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("synthetic-token".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path receipts=directory.resolve("accounts").resolve(account).resolve("operations");Files.createDirectories(receipts);
        Files.writeString(receipts.resolve(logical+".json"),json.writeValueAsString(Map.of("logicalId",logical,"scopeKey",scope,"receipt",Map.of("original",true))));
        var result=json.readTree(service.reconcile("child",logical,scope));assertThat(result.path("logicalId").asText()).isEqualTo(logical);assertThat(result.path("createReceipt").path("original").asBoolean()).isTrue();assertThat(calls.get()).isEqualTo(1);
        props.setToken("different-token");assertThatThrownBy(()->service.reconcile("child",logical,scope)).isInstanceOf(IllegalStateException.class);assertThat(calls.get()).isEqualTo(1);
    }
    @Test void logicalIdentityTravelsOutOfBandAndCannotBeSuppliedAsModelInput()throws Exception{
        var service=mock(TencentRideService.class);String scope="a".repeat(64),logical=UUID.randomUUID().toString();when(service.operationScope()).thenReturn(scope);when(service.execute(anyString(),anyMap())).thenReturn("synthetic-result");
        var tool=new org.celanwang.mio.tool.TencentRideCreateOrderTool(service);var call=mock(io.agentscope.core.tool.ToolCallParam.class);
        var args=new java.util.HashMap<String,Object>(Map.of("price_estimate_keys",List.of("quote"),"stateVersion","v","summary","synthetic"));when(call.getInput()).thenReturn(args);
        when(call.getRuntimeContext()).thenReturn(io.agentscope.core.agent.RuntimeContext.builder().sessionId("child").build());
        tool.callWithOperation(call,logical,scope).block();
        verify(service).execute(eq("child"),argThat(request->logical.equals(request.get("logicalOperationId"))&&scope.equals(request.get("operationScope"))));
        clearInvocations(service);args.put("logicalOperationId",logical);tool.callAsync(call).block();verify(service,never()).execute(anyString(),anyMap());
    }
}
