package com.xxx.ragdoc.application.chat.tool;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import java.util.List;

/**
 * PR-4 / EMS-PR4: 检索类 Tool 的统一 output 标记接口。
 *
 * <p>让 ToolExecutor 可在 ACL post-check 阶段统一过滤无权 Evidence (不依赖每个 Tool 自己实现)。 Tool 的具体 output record
 * 实现本接口, 提供 with Evidences copy 方法。
 */
public interface EvidenceListOutput extends ToolOutput {

    /** 当前 output 持有的 Evidence 列表 (非 null, 可空)。 */
    List<Evidence> evidences();

    /**
     * 用新 Evidence 列表构造一份 output copy (用于 ACL post-check 过滤后重塑 result)。
     *
     * <p>实现应保持 output 的其它字段 (如 rerankState 等) 不变; 只替换 evidences。
     */
    EvidenceListOutput withEvidences(List<Evidence> evidences);
}
