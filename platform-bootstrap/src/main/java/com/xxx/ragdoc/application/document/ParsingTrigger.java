package com.xxx.ragdoc.application.document;

/**
 * 解析触发端口。V1 同步空实现;V3 由 MQ 适配器替换为发事件。
 *
 * <p>存在于 application 层(非 domain) 因为此动作本身是用例步骤,
 * domain 不需要知道"是否异步触发"。
 */
public interface ParsingTrigger {

    /**
     * 触发指定 Document 的解析。返回不抛即视为已派发。
     */
    void trigger(Long documentId);
}
