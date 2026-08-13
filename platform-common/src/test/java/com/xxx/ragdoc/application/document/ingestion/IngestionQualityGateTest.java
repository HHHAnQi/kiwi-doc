package com.xxx.ragdoc.application.document.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.xxx.ragdoc.application.chat.EmbeddingResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class IngestionQualityGateTest {
    private final IngestionQualityGate gate = new IngestionQualityGate();

    @Test
    void rejectsWrongDimensionNonFiniteAndZeroVectors() {
        var wrong = gate.validateEmbeddings(List.of(new EmbeddingResult(new float[3], null)), 1, 0);
        assertThat(wrong.reasons()).contains("EMBEDDING_DIMENSION_INVALID");
        float[] invalid = new float[1024];
        invalid[0] = Float.NaN;
        assertThat(gate.validateEmbeddings(List.of(new EmbeddingResult(invalid, null)), 1, 0).reasons())
                .contains("EMBEDDING_NON_FINITE");
        var zero = gate.validateEmbeddings(List.of(new EmbeddingResult(new float[1024], null)), 1, 0);
        assertThatThrownBy(() -> gate.requirePassed(zero))
                .isInstanceOf(IngestionQualityGate.QualityRejectedException.class);
    }

    @Test
    void acceptsHealthyVector() {
        float[] vector = new float[1024];
        vector[0] = 1f;
        assertThat(gate.validateEmbeddings(List.of(new EmbeddingResult(vector, null)), 1, 0).passed()).isTrue();
    }
}
