package com.xxx.ragdoc.application.document.port;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {

    @Test
    void tiesPreferMoreChannelsThenBetterRankThenChunkId() {
        List<ScoredChunk> first =
                List.of(new ScoredChunk(20L, 1f), new ScoredChunk(10L, .9f));
        List<ScoredChunk> second =
                List.of(new ScoredChunk(10L, 1f), new ScoredChunk(20L, .9f));

        assertThat(ReciprocalRankFusion.fuse(List.of(first, second), 60, 5))
                .extracting(ScoredChunk::chunkId)
                .containsExactly(10L, 20L);
    }

    @Test
    void duplicateWithinChannelContributesOnlyOnce() {
        List<ScoredChunk> malformed =
                List.of(
                        new ScoredChunk(1L, 1f),
                        new ScoredChunk(1L, .9f),
                        new ScoredChunk(2L, .8f));

        List<ScoredChunk> result = ReciprocalRankFusion.fuse(List.of(malformed), 60, 5);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).score()).isEqualTo(1f / 61f);
    }
}
