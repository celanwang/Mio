package org.celanwang.mio.memory;

import io.agentscope.core.message.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MemoryRecordingTest {
    final ProfileDistiller distiller = mock(ProfileDistiller.class);
    final EpisodeRetriever retriever = mock(EpisodeRetriever.class);
    final EpisodeStore store = mock(EpisodeStore.class);
    MioLongTermMemory memory() {return new MioLongTermMemory(distiller, mock(AutomationStore.class),
            mock(ProfileInjector.class), retriever, mock(EmbeddingClient.class), store);}
    Msg message(String id, String text) {return Msg.builder().id(id).role(MsgRole.USER)
            .timestamp("2026-10-22T01:00:00Z").textContent(text).build();}

    @Test void sameTextInDifferentMessagesOrSessionsIsRecordedButHistoryReplayIsIdempotent() {
        Map<String,EpisodeStore.Episode> rows = new HashMap<>();
        when(store.append(any())).thenAnswer(inv -> rows.putIfAbsent(((EpisodeStore.Episode) inv.getArgument(0)).id(), inv.getArgument(0)) == null
                ? EpisodeStore.AppendResult.INSERTED : EpisodeStore.AppendResult.EXISTING);
        var first = message("one", "还是这个地址");var second = message("two", "还是这个地址");
        var memory = memory();memory.record(List.of(first, second), "s1").block();
        memory.record(List.of(first, second), "s1").block();
        memory.record(List.of(first), "s2").block();
        memory().record(List.of(first, second), "s1").block();
        assertThat(rows).hasSize(3);
        assertThat(rows.values()).allSatisfy(e -> assertThat(e.timestamp()).isEqualTo(1792630800000L));
        verify(distiller, times(2)).distill("还是这个地址");
    }

    @Test void failedAppendIsRetriedAndNeverIndexedOrDistilledBeforePersistence() {
        when(store.append(any())).thenReturn(EpisodeStore.AppendResult.FAILED, EpisodeStore.AppendResult.INSERTED);
        var memory = memory();var messages = List.of(message("one", "不要辣椒"));
        memory.record(messages, "s").block();verifyNoInteractions(retriever);
        verify(distiller).distill(null);
        memory.record(messages, "s").block();memory.record(messages, "s").block();
        verify(store, times(2)).append(any());verify(retriever).indexAsync(anyString(), eq("user"), anyLong(), eq("不要辣椒"));
        verify(distiller).distill("不要辣椒");
    }

    @Test void indexingRejectionCanBeRetriedWithoutLosingNewPreferenceOrDuplicatingDistillation() {
        when(store.append(any())).thenReturn(EpisodeStore.AppendResult.INSERTED, EpisodeStore.AppendResult.EXISTING);
        doThrow(new java.util.concurrent.RejectedExecutionException()).doNothing().when(retriever).indexAsync(anyString(), anyString(), anyLong(), anyString());
        var memory = memory();var messages = List.of(message("one", "简洁回复"));
        memory.record(messages, "s").block();memory.record(messages, "s").block();
        verify(retriever, times(2)).indexAsync(anyString(), anyString(), anyLong(), anyString());
        verify(distiller).distill("简洁回复");
    }

    @Test void identityEncodingDistinguishesAbsentAndEmptySessionsAndEmbeddedDelimiters() {
        assertThat(MioLongTermMemory.messageId(null,"id")).isNotEqualTo(MioLongTermMemory.messageId("","id"));
        assertThat(MioLongTermMemory.messageId("a","bc")).isNotEqualTo(MioLongTermMemory.messageId("ab","c"));
    }
}
