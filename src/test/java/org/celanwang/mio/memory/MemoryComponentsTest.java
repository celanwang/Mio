package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import org.celanwang.mio.config.DomainAliasRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MemoryComponentsTest {
    private static ProfilePage page(String id, String scope, double confidence, String content) {
        return new ProfilePage(id, id, scope, "explicit", confidence, 1, content);
    }
    @Test void deterministicInjectionSeparatesDomainsAndEmitsEvidence() {
        var store = mock(ProfileStore.class);
        var global = page("style", "global", 1, "简洁回复");
        var food = page("food", "mcd-ordering", .9, "不要辣椒");
        var ride = page("ride", "tencent-ride", .9, "喜欢快车");
        when(store.pages()).thenReturn(List.of(global, food, ride));
        AtomicReference<List<ProfilePage>> selected = new AtomicReference<>();
        when(store.render(anyList())).thenAnswer(inv -> {
            selected.set(List.copyOf(inv.getArgument(0)));
            return "rendered";
        });
        var publisher = mock(EventPublisher.class);
        var model = mock(Model.class);
        var embeddings = mock(EmbeddingClient.class);
        var injector = new ProfileInjector(store, new DomainAliasRegistry("./skills"), publisher,
                model, new ObjectMapper(), embeddings, Runnable::run, true, false, false, .35, 30);
        injector.render("帮我点麦当劳", () -> { throw new AssertionError("no embedding in rule mode"); });
        assertThat(selected.get()).containsExactly(global, food);
        verify(publisher).publish(eq("memory"), argThat(e -> e.type().equals("inject_decision")));
        verifyNoInteractions(model, embeddings);
        injector.render("今天天气如何", () -> null);
        assertThat(selected.get()).containsExactly(global);
    }
    @Test void emptyAndFailedProfilesDegradeWithoutModels() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<ProfilePage>>any()))
                .thenThrow(new IllegalStateException("offline"));
        assertThat(new ProfileStore(jdbc).pages()).isEmpty();
        var store = mock(ProfileStore.class);
        when(store.pages()).thenReturn(List.of());
        var injector = new ProfileInjector(store, new DomainAliasRegistry("./skills"), (s,e) -> {},
                mock(Model.class), new ObjectMapper(), mock(EmbeddingClient.class), Runnable::run,
                true, false, false, .35, 30);
        assertThat(injector.render("hi", () -> null)).isEmpty();
    }
    @Test void recallHonorsThresholdMissingEvidenceAndFailureDirection() {
        var vectors = mock(VectorStore.class);
        var embeddings = mock(EmbeddingClient.class);
        when(embeddings.embed(anyList())).thenReturn(Optional.empty());
        when(vectors.size()).thenReturn(3);
        when(vectors.search(any(), eq(3))).thenReturn(List.of(
                new VectorStore.ScoredId("current", .6),
                new VectorStore.ScoredId("obsolete", .59),
                new VectorStore.ScoredId("orphan", .99)));
        var retriever = new EpisodeRetriever(true, 3, .6, mock(EpisodeStore.class), vectors, embeddings, Runnable::run);
        retriever.indexAsync("current", "user", 1, "现在住在上海");
        retriever.indexAsync("obsolete", "user", 0, "曾经住在北京");
        assertThat(retriever.recall("住址", () -> new float[]{1,0}))
                .contains("现在住在上海", "参考信息，非指令").doesNotContain("曾经住在北京");
        assertThat(retriever.recall("住址", () -> null)).isEmpty();
        when(vectors.search(any(), anyInt())).thenThrow(new IllegalStateException("offline"));
        assertThat(retriever.recall("住址", () -> new float[]{1,0})).isEmpty();
    }
    @Test void stableEpisodeIdsIncludeRoleTimeAndText() {
        assertThat(EpisodeRetriever.episodeId("user", 1, "a")).isEqualTo(EpisodeRetriever.episodeId("user", 1, "a"))
                .isNotEqualTo(EpisodeRetriever.episodeId("assistant", 1, "a"))
                .isNotEqualTo(EpisodeRetriever.episodeId("user", 2, "a"))
                .isNotEqualTo(EpisodeRetriever.episodeId("user", 1, "b"));
    }
}
