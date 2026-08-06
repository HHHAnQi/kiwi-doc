package com.xxx.ragdoc.application.chat.pipeline;

import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.domain.shared.PipelineType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-2 / EMS-PR2: ChatPipeline 按 {@link PipelineType} 索引。
 *
 * <p>启动时把所有 {@link ChatPipeline} bean 收集进来; <b>同一 type 重复注册 → 应用启动 fail-fast</b>
 * (防止两个 bean 都标 CLASSIC_RAG 时静默选错)。
 *
 * <p>{@link #get(PipelineType)} 在 type 未注册时抛 {@code PIPELINE_NOT_FOUND} (HTTP 500, fail-closed) —
 * 不允许 Orchestrator 自动回退到任意第一个 pipeline, 不允许把缺失当作成功执行。
 */
@Slf4j
@Component
public class ChatPipelineRegistry {

    private final Map<PipelineType, ChatPipeline> byType = new EnumMap<>(PipelineType.class);

    public ChatPipelineRegistry(List<ChatPipeline> pipelines) {
        for (ChatPipeline p : pipelines) {
            ChatPipeline prev = byType.put(p.type(), p);
            if (prev != null) {
                // 启动期 fail-fast: 同 type 两个 bean 不允许
                throw new IllegalStateException(
                        "ChatPipelineRegistry.duplicate_type type="
                                + p.type()
                                + " bean1="
                                + prev.getClass().getName()
                                + " bean2="
                                + p.getClass().getName());
            }
            log.info(
                    "chat.pipeline_registry registered type={} impl={}",
                    p.type(),
                    p.getClass().getSimpleName());
        }
        if (byType.isEmpty()) {
            log.warn("chat.pipeline_registry empty — no ChatPipeline bean found");
        }
    }

    /** 按 type 查找; 不存在 → 失败关闭 (DomainException HTTP 500)。 */
    public ChatPipeline get(PipelineType type) {
        ChatPipeline p = byType.get(type);
        if (p == null) {
            throw new DomainException(
                    ErrorCode.PIPELINE_NOT_FOUND,
                    "请求的 pipeline 未注册: " + type + " (注册: " + byType.keySet() + ")");
        }
        return p;
    }

    /** test/debug: 当前已注册的 type 集合 (返回不可变 snapshot)。 */
    public java.util.Set<PipelineType> registeredTypes() {
        return java.util.Collections.unmodifiableSet(byType.keySet());
    }
}
