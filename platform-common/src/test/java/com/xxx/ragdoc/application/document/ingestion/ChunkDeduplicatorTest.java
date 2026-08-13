package com.xxx.ragdoc.application.document.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.ChunkType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkDeduplicatorTest {
    @Test
    void removesExactDuplicatesAndReassignsSequence() {
        var result = new ChunkDeduplicator().deduplicate(List.of(
                chunk(0, "相同内容段落用于测试去重功能。", "same"),
                chunk(1, "相同内容段落用于测试去重功能。", "same"),
                chunk(2, "完全不同的业务规则说明。", "other")));
        assertThat(result.chunks()).hasSize(2);
        assertThat(result.exactDuplicates()).isEqualTo(1);
        assertThat(result.chunks()).extracting(Chunk::seq).containsExactly(0, 1);
    }

    private static Chunk chunk(int seq, String content, String hash) {
        return new Chunk(null, 1L, seq, ChunkType.TEXT, content, 0, null, null, hash, List.of());
    }
}
