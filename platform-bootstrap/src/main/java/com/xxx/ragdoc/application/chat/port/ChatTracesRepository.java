package com.xxx.ragdoc.application.chat.port;

import com.xxx.ragdoc.application.chat.evidence.EvidenceSnapshot;
import com.xxx.ragdoc.domain.chat.ChatTrace;
import java.util.Optional;

/**
 * chat 持久化端口。domain/application 只认此接口, 具体实现藏于 infrastructure。
 *
 * <p>feedback 通过 {@link #existsByTraceId} 做软引用合法性校验(ADR-0003)。
 */
public interface ChatTracesRepository {

    /** 保存一条 chat 调用记录(与 chat 响应同事务)。 */
    ChatTrace save(ChatTrace chatTrace);

    /**
     * PR-1 / EMS-PR1: 保存 chat trace + 关联的真实 Evidence 快照。 默认实现忽略快照, 走老 {@link #save(ChatTrace)} —
     * 不破坏既有 infrastructure 实现。 只有支持 Evidence 持久化的 adapter 需要 override。
     *
     * @param chatTrace 与 {@link #save(ChatTrace)} 同; evidenceSnapshot 可为 null (无召回 / 未启用)
     * @return 保存后的 domain trace (snapshot 不进 domain record, 仍可经 {@link #findEvidenceByTraceId} 读)
     */
    default ChatTrace save(ChatTrace chatTrace, EvidenceSnapshot evidenceSnapshot) {
        return save(chatTrace);
    }

    /** 校验 trace_id 在系统内是否确实存在(feedback 软引用合法性的唯一防线)。 */
    boolean existsByTraceId(String traceId);

    /**
     * PR-1 / EMS-PR1: 取回某 trace 关联的 Evidence 快照; 不支持持久化快照时返 empty。 让评测/调试单一从 trace_id 还原 Retrieval
     * → Rerank → Context → Evidence。
     */
    default Optional<EvidenceSnapshot> findEvidenceByTraceId(String traceId) {
        return Optional.empty();
    }
}
