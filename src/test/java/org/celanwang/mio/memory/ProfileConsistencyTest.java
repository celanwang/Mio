package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import org.celanwang.mio.controller.ProfileController;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProfileConsistencyTest {
    @Test void failedSnapshotNeverBecomesAnEmptyPromptOrModelWrite() {
        var store = mock(ProfileStore.class);var model = mock(Model.class);
        when(store.snapshot()).thenThrow(new DataAccessResourceFailureException("offline"));
        var distiller = new ProfileDistiller(model, store, mock(PgEntityStore.class), new ObjectMapper(), true);
        var claim=new org.celanwang.mio.memory.jobs.MemoryJob.Claim("job","m",org.celanwang.mio.memory.jobs.MemoryJob.Kind.DISTILL,1,1,1,"owner",Long.MAX_VALUE,null);
        assertThatThrownBy(()->distiller.prepare(new EpisodeStore.Episode("m","s",1,"user","更新地址"),claim)).isInstanceOf(DataAccessResourceFailureException.class);verifyNoInteractions(model);verify(store, never()).saveInferred(any(), any());
    }
    @Test void manualMutationAndReadFailuresReturn503WithoutLeakingDatabaseDetails() throws Exception {
        var store = mock(ProfileStore.class);
        var failure = new DataAccessResourceFailureException("secret database details");
        doThrow(failure).when(store).savePage(any());doThrow(failure).when(store).deletePage(anyString());
        when(store.pagesStrict()).thenThrow(failure);
        var mvc = MockMvcBuilders.standaloneSetup(new ProfileController(store, mock(EntityStore.class), mock(TrustStore.class))).build();
        mvc.perform(put("/api/profile").contentType("application/json").content("{\"title\":\"地址\",\"content\":\"上海\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("画像存储暂不可用，请刷新核对后重试。"));
        mvc.perform(delete("/api/profile").param("id", "地址")).andExpect(status().isServiceUnavailable());
        var response = mvc.perform(get("/api/profile")).andExpect(status().isServiceUnavailable()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("secret database details");
    }
}
