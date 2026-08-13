package com.xxx.ragdoc.application.chat.tool;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import java.util.List;

/**
 * PR-4: 检索类 Tool 统一 output record。实现 {@link EvidenceListOutput} 让 ToolExecutor ACL post-check 过滤。
 *
 * @param evidences 命中的 Evidence 列表 (已 ACL 过滤, 已 metadata filter)
 * @param truncationInfo 是否因 maxResults 被截断, 含原始 hits 数
 */
public record SearchOutput(List<Evidence> evidences, TruncationInfo truncationInfo)
        implements EvidenceListOutput {

    public SearchOutput {
        evidences = evidences == null ? List.of() : List.copyOf(evidences);
        truncationInfo =
                truncationInfo == null
                        ? new TruncationInfo(false, evidences.size(), evidences.size())
                        : truncationInfo;
    }

    @Override
    public List<Evidence> evidences() {
        return evidences;
    }

    @Override
    public SearchOutput withEvidences(List<Evidence> newEvidences) {
        List<Evidence> safe = newEvidences == null ? List.of() : List.copyOf(newEvidences);
        // ACL 过滤不影响 truncation 描述 (说明原始 hits 数)
        return new SearchOutput(safe, truncationInfo);
    }

    public record TruncationInfo(boolean truncated, int originalCount, int returnedCount) {}
}
