package com.xxx.ragdoc.application.chat.verification.port;

import com.xxx.ragdoc.application.chat.verification.VerificationResult;
import java.util.List;

/**
 * Task 7: Citation Verification 端口 — NLI 判定 answer 是否被 evidence 支持。
 *
 * <p>实现: {@code infrastructure.verification.LlmCitationVerifier} (走 LlmRouter fallback route +
 * 独立 CircuitBreaker {@code citation-verifier-llm})。
 *
 * <p>语义约定 (任务文档对齐):
 *
 * <ul>
 *   <li>输入: 生成答案 {@code answer} + evidence 列表 (含 chunkId + 文本)
 *   <li>输出: {@link VerificationResult}, 含每 evidence 的 NLI verdict + 整体 score
 *   <li>调用方 ChatService 根据 {@link VerificationResult.Outcome} + threshold 决策:
 *       PASS → 保持 OK; FAIL → REFUSE / REGENERATE / WARN_ONLY
 * </ul>
 *
 * <p>实现侧失败 (LLM 异常/超时/熔断/JSON 解析错) 必须 fallback 到 {@link VerificationResult#error},
 * 不抛 — 主 chat 流程绝不被 verification 子任务挂。
 *
 * <p>Port 放 platform-common 用简单的 {@link Evidence} record (不引用 ChatResult, 跨模块解耦);
 * ChatService 在应用层把 ChatResult.Citation 转成 {@link Evidence} 列表传入。
 */
public interface CitationVerifierPort {

    /**
     * 验证 evidences 是否支持 answer。
     *
     * @param answer LLM 生成的答案 (candidate)
     * @param evidences 答案引用的 evidence 列表 (含 chunkId + 文本)
     */
    VerificationResult verify(String answer, List<Evidence> evidences);

    /** port 自带的 evidence 值对象, 跨模块解耦 (不依赖 ChatResult 位置)。 */
    record Evidence(long chunkId, String text) {}
}

