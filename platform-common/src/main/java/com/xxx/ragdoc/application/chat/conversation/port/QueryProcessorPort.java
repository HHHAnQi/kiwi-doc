package com.xxx.ragdoc.application.chat.conversation.port;

import com.xxx.ragdoc.application.chat.conversation.EnhanceResult;
import com.xxx.ragdoc.domain.auth.Principal;

/**
 * Task 6: Query Enhancement 端口 (单轮 query rewrite + expansion)。
 *
 * <p>职责: 服务于 RetrieveService / ChatService — 把用户原 query 改写为更利于检索的形态:
 *
 * <ul>
 *   <li><b>Rewrite</b>: 俚语 / 别名 / 缩略 → 规范化术语
 *       <ul>
 *         <li>"Rancher 部署" → "Rancher 容器编排平台部署"
 *         <li>"上面那个" → (单轮, 无 history) 原样
 *       </ul>
 *   <li><b>Expansion</b>: 复杂问题拆多元查询 (multiview retrieval)
 *       <ul>
 *         <li>"Dubbo 服务级别的超时配置项有哪些?" → [主, "Dubbo provider timeout", "Dubbo consumer timeout",
 *             "Dubbo 方法级 timeout"]
 *       </ul>
 * </ul>
 *
 * <p>与 {@link QueryContextualizerPort} 的关系:
 *
 * <ul>
 *   <li>Contextualizer 是<b>多轮代词解析</b> (依赖 history 解决"它/那个"指代)
 *   <li>QueryProcessor 是<b>单轮术语增强</b> (不依赖 history)
 *   <li>串联顺序: 用户 query → Contextualizer (如有 history) → QueryProcessor → Retriever
 * </ul>
 *
 * <p>实现侧约定:
 *
 * <ul>
 *   <li>异常 / LLM 失败 / 熔断 → 返回 {@link EnhanceResult#failed}, 不挂主流程
 *   <li>parrot-echo (rewrite == original) → {@link EnhanceResult#skipped}, 避免无意义循环
 * </ul>
 *
 * <p>Port 放 platform-common 是为让 ChatService / RetrieveService (application 层) 依赖接口而非 infra, 维持
 * ArchUnit "application 不依赖 infrastructure" 纪律。
 */
public interface QueryProcessorPort {

    /**
     * 单轮增强 query。
     *
     * @param query 原 query (已走过 Contextualizer 代词解析的 standalone query)
     * @param principal 当前调用 principal, 让 prompt 可带租户/角色上下文 (可选; null=匿名)
     */
    EnhanceResult enhance(String query, Principal principal);
}
