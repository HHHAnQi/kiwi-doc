package com.xxx.ragdoc.application.chat.port;

import com.xxx.ragdoc.domain.chat.ChatTrace;

/**
 * chat 持久化端口。domain/application 只认此接口, 具体实现藏于 infrastructure。
 *
 * <p>feedback 通过 {@link #existsByTraceId} 做软引用合法性校验(ADR-0003)。
 */
public interface ChatTracesRepository {

    /** 保存一条 chat 调用记录(与 chat 响应同事务)。 */
    ChatTrace save(ChatTrace chatTrace);

    /** 校验 trace_id 在系统内是否确实存在(feedback 软引用合法性的唯一防线)。 */
    boolean existsByTraceId(String traceId);
}
