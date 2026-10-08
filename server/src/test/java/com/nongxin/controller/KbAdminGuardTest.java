package com.nongxin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nongxin.security.KbAdminGuard;
import com.nongxin.service.EmbeddingService;
import com.nongxin.service.KnowledgeGraphService;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.VectorIndexService;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

class KbAdminGuardTest {
    private final KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    private final EmbeddingService embedding = mock(EmbeddingService.class);
    private final VectorIndexService vectors = mock(VectorIndexService.class);
    private final KnowledgeGraphService graph = mock(KnowledgeGraphService.class);

    @Test
    void maintenanceIsDisabledWithoutAnOperatorToken() {
        var guard = new KbAdminGuard("");
        assertThatThrownBy(
                        () ->
                                new KbIndexController(library, embedding, vectors, graph, guard)
                                        .reindex(null, Map.of("force", true)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(
                        () ->
                                new RetrievalEvalController(library, embedding, vectors, guard)
                                        .evaluate(null, 5))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(library, embedding, vectors, graph);
    }

    @Test
    void aValidOperatorTokenStillCannotInjectAnEmbeddingKeyThroughTheRequest() {
        String secret = "test-only-admin-token-long-enough";
        var guard = new KbAdminGuard(secret);
        assertThatThrownBy(() -> guard.require("different-admin-token-value"))
                .isInstanceOf(ResponseStatusException.class);
        var response =
                new KbIndexController(library, embedding, vectors, graph, guard)
                        .reindex(secret, Map.of("apiKey", "fake-key-not-accepted"));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(library, embedding, vectors, graph);
    }

    @Test
    void statusAndReindexUseTheSameLocalAndHybridModeDescriptions() {
        String token = "test-only-admin-token-long-enough";
        var controller =
                new KbIndexController(library, embedding, vectors, graph, new KbAdminGuard(token));
        when(vectors.ready()).thenReturn(true);
        assertThat(controller.status().get("mode")).isEqualTo("lexical+graph(applicability)");
        when(embedding.available()).thenReturn(true);
        for (boolean ready : new boolean[] {false, true}) {
            when(vectors.ready()).thenReturn(ready);
            String expected =
                    ready
                            ? "hybrid(lexical+vector+graph, RRF+applicability)"
                            : "lexical+graph(applicability)";
            assertThat(controller.status().get("mode")).isEqualTo(expected);
            var response = controller.reindex(token, Map.of());
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(((Map<?, ?>) response.getBody()).get("mode")).isEqualTo(expected);
        }
    }
}
