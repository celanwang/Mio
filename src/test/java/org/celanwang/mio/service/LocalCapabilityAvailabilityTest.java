package org.celanwang.mio.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Toolkit;
import org.celanwang.mio.config.*;
import org.celanwang.mio.orchestration.CapabilityAvailability;
import org.celanwang.mio.security.ToolRegistrationGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalCapabilityAvailabilityTest {
    @TempDir Path directory;
    @Test void rideInspectionNeedsNoProcessAndDoesNotRevealCredentialsOrCreateState()throws Exception{
        var properties=new TencentRideProperties();properties.setEnabled(false);properties.setToken("SYNTHETIC_SECRET");properties.setDataDirectory(directory.resolve("state"));
        var service=new TencentRideService(properties,new ObjectMapper(),"skills");
        assertThat(service.availability().code()).isEqualTo("DISABLED");properties.setEnabled(true);properties.setToken("");
        assertThat(service.availability().code()).isEqualTo("CREDENTIAL_REQUIRED");properties.setToken("SYNTHETIC_SECRET");properties.setPython(directory.resolve("missing").toString());
        assertThat(service.availability().code()).isEqualTo("PYTHON_UNAVAILABLE");
        Path binary=directory.resolve("fake-python");Files.writeString(binary,"not Python and must never be executed");assertThat(binary.toFile().setExecutable(true)).isTrue();properties.setPython(binary.toString());
        assertThat(service.availability().state()).isEqualTo(CapabilityAvailability.State.UNKNOWN);
        assertThat(service.availability().message()).doesNotContain("SYNTHETIC_SECRET",directory.toString());assertThat(Files.exists(properties.getDataDirectory())).isFalse();
    }
    @Test void weiboDiscoveryDoesNotRefreshAuthorizationOrConnect(){
        var properties=new WeiboProperties();properties.setEnabled(true);var oauth=mock(WeiboOAuthService.class);
        var service=new WeiboMcpService(properties,oauth,new Toolkit(),new ToolRegistrationGuard());
        var state=service.availability();assertThat(state.state()).isEqualTo(CapabilityAvailability.State.PREPARATION_REQUIRED);assertThat(state.preparationTools()).containsExactly("prepare_weibo");
        properties.setEnabled(false);assertThat(service.availability().state()).isEqualTo(CapabilityAvailability.State.UNAVAILABLE);verifyNoInteractions(oauth);
    }
}
