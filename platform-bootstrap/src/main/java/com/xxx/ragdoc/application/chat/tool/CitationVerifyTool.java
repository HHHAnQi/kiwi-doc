package com.xxx.ragdoc.application.chat.tool;

import com.xxx.ragdoc.application.chat.verification.VerificationResult;
import com.xxx.ragdoc.application.chat.verification.port.CitationVerifierPort;
import com.xxx.ragdoc.common.exception.ErrorCode;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * PR-4 / EMS-PR4: citation_verify Tool — 把 {@link CitationVerifierPort} 暴露为 Agent Tool。
 *
 * <h2>关键守门</h2>
 *
 * <ul>
 *   <li><b>不扩大检索</b>: 只验证 input.evidences 已授权集 (调用方必须自己先 semantic_search+ACL 过滤); Tool 不调
 *       RetrieveService
 *   <li><b>SKIPPED 安全</b>: 当 {@code rag.citation-verifier.enabled=false} (默认) 时返回 {@link
 *       ToolStatus#SUCCESS} + {@code outcome=SKIPPED}, 让 Planner 知道结果可信度低
 *   <li><b>不覆盖最终答案</b>: Tool 只返评分, Executor / Orchestrator 决定是否据此拒绝生成
 * </ul>
 */
@Slf4j
@Component
public class CitationVerifyTool implements AgentTool<CitationVerifyInput, CitationVerifyOutput> {

    public static final String NAME = "citation_verify";
    public static final String VERSION = "v1";

    /** ObjectProvider 让 Tool 总是可装配; verifier bean 不存在 (功能关) 时走 SKIPPED 路径。 */
    private final ObjectProvider<CitationVerifierPort> verifierProvider;

    public CitationVerifyTool(ObjectProvider<CitationVerifierPort> verifierProvider) {
        this.verifierProvider = verifierProvider;
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                NAME,
                VERSION,
                "引用核验: 用 NLI LLM 判断 claim 是否被给定 Evidence 集合支持。"
                        + "适用: 已 retrieved 出 Evidence 后, 决定是否拒答 / 修改 claim。"
                        + "不适用: 未授权 Evidence (Tool 不自扩检索), 不生成新答案。",
                "v1",
                "v1",
                ToolPermission.VERIFY_CITATION,
                Duration.ofSeconds(15),
                20,
                true,
                ToolCostCategory.LLM);
    }

    @Override
    public Class<CitationVerifyInput> inputType() {
        return CitationVerifyInput.class;
    }

    @Override
    public Class<CitationVerifyOutput> outputType() {
        return CitationVerifyOutput.class;
    }

    @Override
    public ToolResult<CitationVerifyOutput> execute(
            CitationVerifyInput input, ToolExecutionContext context) {
        long t0 = System.currentTimeMillis();
        CitationVerifierPort verifier = verifierProvider.getIfAvailable();
        if (verifier == null) {
            // 功能关 → SKIPPED, 不算失败 (Planner 仍可继续, 但要意识到分不可信)
            CitationVerifyOutput skipped =
                    new CitationVerifyOutput("SKIPPED", 0.0, List.of(), true);
            return ToolResult.success(
                    context.requestId() + "-cv",
                    NAME,
                    VERSION,
                    skipped,
                    System.currentTimeMillis() - t0,
                    java.util.Map.of("skipped_reason", "verifier_disabled"));
        }

        // 把统一 Evidence 转 CitationVerifierPort.Evidence (验证只读 text 字段)
        List<CitationVerifierPort.Evidence> portEvidences =
                input.evidences().stream()
                        .map(e -> new CitationVerifierPort.Evidence(e.chunkId(), e.content()))
                        .toList();

        VerificationResult vr;
        try {
            vr = verifier.verify(input.claim(), portEvidences);
        } catch (RuntimeException ex) {
            log.warn(
                    "tool.citation_verify.failed claim_len={} err={}",
                    input.claim().length(),
                    ex.toString());
            return ToolResult.failure(
                    context.requestId() + "-cv",
                    NAME,
                    VERSION,
                    ToolStatus.DEPENDENCY_UNAVAILABLE,
                    ToolError.dependencyError(
                            ErrorCode.TOOL_DEPENDENCY_UNAVAILABLE.code(),
                            "引用核验 LLM 暂不可用",
                            "verification-llm",
                            true),
                    System.currentTimeMillis() - t0,
                    java.util.Map.of());
        }
        if (vr == null) {
            vr = VerificationResult.error("verifier returned null");
        }
        CitationVerifyOutput out = CitationVerifyOutput.from(vr);
        ToolStatus status = ToolStatus.SUCCESS;
        if (vr.outcome() == VerificationResult.Outcome.ERROR) {
            // verifier 内部错误 (但仍非异常) → 不当作 SUCCESS, 用 DEPENDENCY_UNAVAILABLE 让上层重试或保守
            return ToolResult.failure(
                    context.requestId() + "-cv",
                    NAME,
                    VERSION,
                    ToolStatus.DEPENDENCY_UNAVAILABLE,
                    ToolError.dependencyError(
                            ErrorCode.TOOL_DEPENDENCY_UNAVAILABLE.code(),
                            vr.errorMessage() == null
                                    ? "verifier internal error"
                                    : vr.errorMessage(),
                            "verification-llm",
                            true),
                    System.currentTimeMillis() - t0,
                    java.util.Map.of());
        }
        return ToolResult.success(
                context.requestId() + "-cv",
                NAME,
                VERSION,
                out,
                System.currentTimeMillis() - t0,
                java.util.Map.of("outcome", vr.outcome().name()));
    }
}
