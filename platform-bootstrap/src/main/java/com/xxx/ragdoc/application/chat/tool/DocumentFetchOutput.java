package com.xxx.ragdoc.application.chat.tool;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import java.util.List;

/**
 * PR-4 / EMS-PR4: document_fetch 的输出 = chunk(s) 转 Evidence (单条或多条)。
 *
 * <p>用 {@link EvidenceListOutput} 让 Executor 的 ACL post-check 统一过滤。
 *
 * @param evidences 命中 chunks (含 parent/neighbor); count ≤ maxResults (descriptor.maxResults=10)
 * @param fetchedChunkIds 工具实际拿到的 chunkId (含 neighbor), 便于 trace / 调试
 * @param mode 描述本次取到的模式 (SELF / NEIGHBOR / PARENT), 用于 trace
 */
public record DocumentFetchOutput(
        List<Evidence> evidences, List<Long> fetchedChunkIds, String mode)
        implements EvidenceListOutput {

    public DocumentFetchOutput {
        evidences = evidences == null ? List.of() : List.copyOf(evidences);
        fetchedChunkIds = fetchedChunkIds == null ? List.of() : List.copyOf(fetchedChunkIds);
        mode = mode == null ? "SELF" : mode;
    }

    @Override
    public List<Evidence> evidences() {
        return evidences;
    }

    @Override
    public DocumentFetchOutput withEvidences(List<Evidence> newEvidences) {
        return new DocumentFetchOutput(
                newEvidences == null ? List.of() : List.copyOf(newEvidences),
                fetchedChunkIds,
                mode);
    }
}
