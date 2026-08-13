package com.xxx.ragdoc.interfaces.rest.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.xxx.ragdoc.domain.shared.ChatMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * chat 接口请求 DTO(与 api-contracts.md §D1 对齐)。
 *
 * <p>V3: source/version/language 为可选业务元数据过滤条件, 任一非空即限定向量检索范围(逻辑 AND)。
 *
 * <p>字段兼容: 项目全局 Jackson 命名策略为 SNAKE_CASE, 外部 JSON 必须传 {@code doc_id}/{@code top_k}/{@code
 * corrected_answer} 等 snake_case 键。为防止调用方误传 camelCase 静默丢字段(实测曾 导致 docId 过滤看似失效), 通过
 * {@code @JsonAlias} 同时接受 camelCase 别名。响应仍按 snake_case 输出。
 *
 * <p>PR-2 / EMS-PR2: 新增 {@code mode} 字段 ({@link ChatMode})。缺失或 null → 默认 {@link ChatMode#AUTO}
 * (老客户端兼容); 未知值由 Jackson 反序列化抛错经 GlobalExceptionHandler 转 400 SYS_INVALID_ARGUMENT。
 *
 * <p>注: {@code mode} <b>不能</b> 修改 tenantId / userId / ACL / 是否为管理员; 仅用于 Orchestrator 路由选择。
 */
@Schema(name = "ChatRequest")
public record ChatRequest(
        @Schema(description = "用户问题, 1-500 字", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "query 不能为空")
                @Size(max = 500, message = "query 长度不能超过 500")
                String query,
        @JsonAlias("docId") @Schema(description = "限定文档 ID, 可选; 不传=跨全库") Long docId,
        @JsonAlias("topK")
                @Schema(description = "召回 top_k, 默认 5, 范围 [1, 20]")
                @Min(value = 1, message = "top_k 最小 1")
                @Max(value = 20, message = "top_k 最大 20")
                Integer topK,
        @Schema(description = "限定来源组件(dubbo/nacos/seata/rocketmq/sentinel), 可选") String source,
        @Schema(description = "限定版本, 可选") String version,
        @Schema(description = "限定语言(zh/en), 可选") String language,
        @JsonAlias("conversationId")
                @Schema(description = "Phase 1 (ADR-0011): 会话 ID, 可选; 不传=单轮 stateless, 传则启用多轮")
                String conversationId,
        @JsonAlias("mode")
                @Schema(
                        description =
                                "PR-2: 执行模式 RAG/AGENTIC/AUTO; 缺失或 null=AUTO; AGENTIC 暂未启用返回 422")
                ChatMode mode) {

    /** 老调用方兼容构造(无元数据过滤, 无 mode)。 */
    public ChatRequest(String query, Long docId, Integer topK) {
        this(query, docId, topK, null, null, null, null, null);
    }

    /** PR-2 之前 7 字段老调用方构造(无 mode)。 */
    public ChatRequest(
            String query,
            Long docId,
            Integer topK,
            String source,
            String version,
            String language,
            String conversationId) {
        this(query, docId, topK, source, version, language, conversationId, null);
    }
}
