package org.celanwang.mio.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.ToolCallParam;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CapabilityPackagesTest {
    @TempDir Path directory;
    @Test void onlyEnabledTrustedManifestsAddNewPreparationCapabilities() throws Exception {
        Files.writeString(directory.resolve("review.json"),"{\"id\":\"review\",\"name\":\"review\",\"description\":\"review\",\"kind\":\"LOCAL_DOCUMENTS\",\"prompt\":\"review\",\"enabled\":true}");
        var packages=new CapabilityPackages(directory,Path.of("skills"),mock(JdbcDocuments.class),new ObjectMapper(),name->null);
        assertThat(packages.definitions()).hasSize(1);var capability=packages.definitions().get(0).capability();assertThat(capability.outputContracts()).extracting(org.celanwang.mio.orchestration.CapabilityOutputContract::id).containsExactly("report");assertThat(capability.id()).isEqualTo("review");
    }
    @Test void remoteBridgeSendsPreparationOnlyAndKeepsRepliesUnverified() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var request=new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/prepare",exchange->{request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] body="{\"protocol\":\"mio.prepare.v1\",\"state\":\"COMPLETED\",\"reply\":\"draft\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try{var tool=new RemotePreparationTool("http://127.0.0.1:"+server.getAddress().getPort()+"/prepare","fixture",new ObjectMapper(),true);var result=tool.callAsync(ToolCallParam.builder().input(Map.of("request","compare sources")).build()).block();assertThat(request.get()).contains("\"mode\":\"PREPARE\"").doesNotContain("approval");assertThat(result).isNotNull();assertThat(((io.agentscope.core.message.TextBlock)result.getOutput().get(0)).getText()).contains("UNVERIFIED_REPORT");}finally{server.stop(0);}
        assertThatThrownBy(()->new RemotePreparationTool("http://example.com/prepare","",new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
    }
}
