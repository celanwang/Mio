package org.celanwang.mio.orchestration;

import org.celanwang.mio.model.dto.ChatRequest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SelectedEffectBoundaryTest {
    @Test void configuredWriteCannotUseReadonlyProviderMetadataToBypassPreparationScope(){
        var execution=org.mockito.Mockito.mock(TaskExecution.class);var task=org.mockito.Mockito.mock(TaskRecord.class);org.mockito.Mockito.when(execution.businessActive()).thenReturn(task);org.mockito.Mockito.when(task.spec()).thenReturn(new TaskSpec("prepare","domain","prepare",List.of(),List.of(),TaskScope.PREPARE));
        var delegate=new io.agentscope.core.tool.ToolBase(io.agentscope.core.tool.ToolBase.builder().name("provider_publish").description("provider incorrectly says read only").inputSchema(Map.of()).readOnly(true)){};
        var tool=new GuardedDomainTool(delegate,execution,new org.celanwang.mio.security.PolicyEngine("shadow"),()->CapabilityAvailability.of(CapabilityAvailability.State.UNKNOWN,"CONFIGURED","configured"),true);
        assertThat(tool.checkPermissions(Map.of(),null).block().getBehavior()).isEqualTo(io.agentscope.core.permission.PermissionBehavior.DENY);
    }
    @Test void approvalCannotBypassSelectedConditionsBeforeProviderInvocation(){
        var fixture=new DomainFixture();fixture.budget.bindEffectGuard(task->{throw new IllegalStateException("当前条件不满足");});
        fixture.call("create-order",Map.of());var pending=fixture.runtime.submit("root",fixture.spec("selected",TaskScope.REQUEST_APPROVAL),fixture.budget).block();
        fixture.call("mio_finish_task",Map.of("text","report"));fixture.runtime.approve("root",List.of(new ChatRequest.Confirmation(pending.approvals().get(0).id(),true)),fixture.budget).block();
        assertThat(fixture.writes.get()).isZero();assertThat(fixture.store.task(pending.id()).orElseThrow().effectStarted()).isFalse();
    }
    @Test void validConditionsStillNeedSpecificApprovalAndPermitOnlyOneSubmission(){
        var fixture=new DomainFixture();fixture.budget.bindEffectGuard(task->System.currentTimeMillis()+30000);
        fixture.call("create-order",Map.of());var pending=fixture.runtime.submit("root",fixture.spec("selected",TaskScope.REQUEST_APPROVAL),fixture.budget).block();assertThat(fixture.writes.get()).isZero();
        fixture.call("mio_finish_task",Map.of("text","result unknown"));fixture.runtime.approve("root",List.of(new ChatRequest.Confirmation(pending.approvals().get(0).id(),true)),fixture.budget).block();
        assertThat(fixture.writes.get()).isEqualTo(1);assertThat(fixture.store.task(pending.id()).orElseThrow().effectStarted()).isTrue();
    }
}
