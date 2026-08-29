package com.xxx.ragdoc.infrastructure.milvus;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 5: RRFFusioner 单测。
 *
 * <p>关键不变量:
 *
 * <ul>
 *   <li>RRF 公式: score = sum(1/(k + rank)), rank 从 1 起
 *   <li>同 chunkId 出现在多路时分数累加
 *   <li>输出按融合分数降序, 截 topK
 *   <li>单路空集合 → 融合降级到另一路 (rank 仍按 1,2,3..)
 *   <li>全空集 → 返空列表
 * </ul>
 */
@DisplayName("Task 5 RRFFusioner")
class RRFFusionerTest {

    private final RRFFusioner fusioner = new RRFFusioner();

    @Test
    @DisplayName("dense + sparse 同 chunkId 出现在两路 → 累加 RRF 分数 (排名靠前)")
    void overlapAccumulatesScore() {
        List<ScoredChunk> dense = List.of(new ScoredChunk(1L, 0.9f), new ScoredChunk(2L, 0.8f));
        List<ScoredChunk> sparse = List.of(new ScoredChunk(2L, 5.1f), new ScoredChunk(1L, 4.0f));

        List<ScoredChunk> fused = fusioner.fuse(List.of(dense, sparse), 60, 5);

        // chunk 2: dense rank=2 (1/62) + sparse rank=1 (1/61) ≈ 0.01613 + 0.01639
        // chunk 1: dense rank=1 (1/61) + sparse rank=2 (1/62) ≈ 0.01639 + 0.01613
        // → chunk 2 略高 (sparse 名次更靠前)。手算: 0.0325 vs 0.0325, 几乎相等。
        // 严格: chunk2 = 1/62 + 1/61 = 0.03252; chunk1 = 1/61 + 1/62 = 0.03252 相等
        // 但浮点顺序差异, 这里仅断言两个 chunk 都在, 且都是精确 RRF 分数
        assertThat(fused).hasSize(2);
        assertThat(fused.stream().map(ScoredChunk::chunkId)).containsExactly(1L, 2L);
        // 双方分数近似相等 (RRF k=60, 同chunk两侧rank互换, 总和相同)
        assertThat(Math.abs(fused.get(0).score() - fused.get(1).score())).isLessThan(1e-5f);
    }

    @Test
    @DisplayName("仅出现在 dense 的 chunk (sparse 没命中) → 单路分数")
    void denseOnlyChunkHasLowerScore() {
        // dense: 1, 2; sparse: 仅 1 (chunk 2 没匹配)
        List<ScoredChunk> dense = List.of(new ScoredChunk(1L, 0.9f), new ScoredChunk(2L, 0.8f));
        List<ScoredChunk> sparse = List.of(new ScoredChunk(1L, 5.0f));
        List<ScoredChunk> fused = fusioner.fuse(List.of(dense, sparse), 60, 5);

        // chunk 1 双路累加, chunk 2 只 dense 路 → chunk1 必胜
        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).chunkId()).isEqualTo(1L);
        assertThat(fused.get(0).score()).isGreaterThan(fused.get(1).score());
        // chunk 2 分数 = 1/(60+2) = 1/62
        assertThat(fused.get(1).score()).isCloseTo(1f / 62f, within(1e-6f));
    }

    @Test
    @DisplayName("sparse 完全空 (BM25 失败) → 降级到 dense 路, 等价于 dense 仅排序")
    void emptySparseDegradesToDenseRanking() {
        List<ScoredChunk> dense = List.of(new ScoredChunk(1L, 0.9f), new ScoredChunk(2L, 0.8f));
        List<ScoredChunk> fused = fusioner.fuse(List.of(dense, List.of()), 60, 5);
        // rank 顺序保持 1, 2
        assertThat(fused).extracting(ScoredChunk::chunkId).containsExactly(1L, 2L);
        assertThat(fused.get(0).score()).isCloseTo(1f / 61f, within(1e-6f));
    }

    @Test
    @DisplayName("topK 截断: 仅返回前 topK 个")
    void truncateToTopK() {
        List<ScoredChunk> dense =
                List.of(
                        new ScoredChunk(10L, 0.9f),
                        new ScoredChunk(20L, 0.8f),
                        new ScoredChunk(30L, 0.7f),
                        new ScoredChunk(40L, 0.6f));
        List<ScoredChunk> fused = fusioner.fuse(List.of(dense), 60, 2);
        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).chunkId()).isEqualTo(10L);
        assertThat(fused.get(1).chunkId()).isEqualTo(20L);
    }

    @Test
    @DisplayName("全空输入 → 返空列表")
    void emptyInputReturnsEmpty() {
        List<ScoredChunk> fused = fusioner.fuse(List.of(List.of()), 60, 5);
        assertThat(fused).isEmpty();
    }

    @Test
    @DisplayName("k 改变影响分数 (敏感性 ablation)")
    void kImpactsScore() {
        List<ScoredChunk> dense = List.of(new ScoredChunk(1L, 0.9f));
        // k=10: 1/11 ≈ 0.091; k=100: 1/101 ≈ 0.0099
        List<ScoredChunk> fused10 = fusioner.fuse(List.of(dense), 10, 1);
        List<ScoredChunk> fused100 = fusioner.fuse(List.of(dense), 100, 1);
        assertThat(fused10.get(0).score()).isGreaterThan(fused100.get(0).score());
    }

    @Test
    @DisplayName("同一路重复 chunk 只按最佳排名贡献一次")
    void duplicateWithinOneChannelContributesOnlyOnce() {
        List<ScoredChunk> malformedDense =
                List.of(
                        new ScoredChunk(1L, 0.9f),
                        new ScoredChunk(1L, 0.8f),
                        new ScoredChunk(2L, 0.7f));

        List<RRFFusioner.FusionResult> detailed =
                fusioner.fuseDetailed(List.of(malformedDense), 60, 5);

        assertThat(detailed).hasSize(2);
        assertThat(detailed.get(0).chunkId()).isEqualTo(1L);
        assertThat(detailed.get(0).fusedScore()).isCloseTo(1d / 61d, withinDouble(1e-9));
        assertThat(detailed.get(0).matchedChannelCount()).isEqualTo(1);
        assertThat(detailed.get(0).contributions()).hasSize(1);
    }

    @Test
    @DisplayName("融合分数相同时按命中路数、最佳排名、chunkId 确定性排序")
    void tiesUseDeterministicOrdering() {
        List<ScoredChunk> dense = List.of(new ScoredChunk(20L, 0.9f), new ScoredChunk(10L, 0.8f));
        List<ScoredChunk> sparse = List.of(new ScoredChunk(10L, 9f), new ScoredChunk(20L, 8f));

        for (int i = 0; i < 20; i++) {
            assertThat(fusioner.fuse(List.of(dense, sparse), 60, 5))
                    .extracting(ScoredChunk::chunkId)
                    .containsExactly(10L, 20L);
        }
    }

    @Test
    @DisplayName("topK 非法时返回空结果")
    void invalidTopKReturnsEmpty() {
        assertThat(fusioner.fuse(List.of(List.of(new ScoredChunk(1L, 1f))), 60, 0)).isEmpty();
    }

    private static org.assertj.core.data.Offset<Float> within(float tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }

    private static org.assertj.core.data.Offset<Double> withinDouble(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}
