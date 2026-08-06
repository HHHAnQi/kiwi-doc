package com.xxx.ragdoc.application.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PR-6b.2: {@link EvidenceAccumulatorFactory} 单测。
 *
 * <p>核心断言: 两次 create() 必须返回<b>不同实例</b> (per-Run 隔离), 否则并发 Run 跨租户污染 (Revision §1)。
 */
@DisplayName("EvidenceAccumulatorFactory - per-Run 独立实例")
class EvidenceAccumulatorFactoryTest {

    @Test
    @DisplayName("create() 返回的两次 accumulator 必须是不同对象 (跨 Run 隔离)")
    void twoCreationsReturnDifferentInstances() {
        EvidenceAccumulatorFactory f = new EvidenceAccumulatorFactory();
        EvidenceAccumulator a1 = f.create("tA", 20, 4000);
        EvidenceAccumulator a2 = f.create("tA", 20, 4000);
        assertThat(a1).isNotSameAs(a2);
    }

    @Test
    @DisplayName("非法 tenantId → fail-closed")
    void blankTenantRejected() {
        EvidenceAccumulatorFactory f = new EvidenceAccumulatorFactory();
        assertThatThrownBy(() -> f.create("", 20, 4000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.create(null, 20, 4000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("非正 maxEvidence / maxEvidenceTokens 时使用默认值")
    void defaultLimitsApplied() {
        EvidenceAccumulatorFactory f = new EvidenceAccumulatorFactory();
        EvidenceAccumulator a = f.create("tA", 0, -1);
        var desc = a.describeLimits();
        assertThat(desc.get("maxEvidence")).isEqualTo(20);
        assertThat(desc.get("maxEvidenceTokens")).isEqualTo(4000);
    }
}
