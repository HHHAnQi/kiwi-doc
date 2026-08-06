package com.xxx.ragdoc.application.chat.agent;

import org.springframework.stereotype.Component;

/**
 * PR-6b.2 / EMS-PR6 §8: {@link EvidenceAccumulator} 工厂 (Revision §1 — per-Run 实例)。
 *
 * <p>关键设计: <b>不</b>用单例 EvidenceAccumulator Bean, 否则并发 Run 共享状态导致跨租户污染。
 * 本 Factory 是 Spring Bean, 但每次 {@link #create} 返回<b>全新的、非 Bean</b> 实例,
 * AgentRunExecutor 每 Run 创建一次, 局部持有。
 */
@Component
public class EvidenceAccumulatorFactory {

    /**
     * 构造每 Run 独立 accumulator。
     *
     * @param tenantId 来自服务端 Principal (Evidence.of 强制注入, 不接受 caller 传入)
     * @param maxEvidence 来自 AgentExecutionPolicy
     * @param maxEvidenceTokens 来自 AgentExecutionPolicy
     */
    public EvidenceAccumulator create(String tenantId, int maxEvidence, int maxEvidenceTokens) {
        return new EvidenceAccumulator(tenantId, maxEvidence, maxEvidenceTokens);
    }
}
