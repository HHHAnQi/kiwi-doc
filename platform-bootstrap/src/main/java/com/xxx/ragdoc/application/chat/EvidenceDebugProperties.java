package com.xxx.ragdoc.application.chat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * PR-1 / EMS-PR1: 控制 Chat 响应是否回传真实 Evidence 三段快照。
 *
 * <h2>安全约束 (硬不变量)</h2>
 *
 * <ul>
 *   <li>默认关闭 — 普通生产响应<b>不</b>暴露 tenantId / contentHash / content 全文等内部字段。
 *   <li>{@link #enabled} 是服务端总闸, 关时无论请求头如何都不泄露。
 *   <li>请求侧用 {@code X-Debug-Evidence: true} 头显式开启 (评测 / 调试场景)。
 *   <li>今后的生产路径只返回安全 Citation (chunkId/docId/page/snippet/sectionPath/verifyScore)。
 * </ul>
 *
 * <p>评测 runner 用此开关让一次 chat 同时拿到 answer + evidence, 不再独立调 {@code /retrieve}; 这是 EMS-PR1 "评测 Context
 * 与 Chat 实际 Context 一致" 的关键。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.evidence")
public class EvidenceDebugProperties {

    /** 服务端总闸 {@code rag.evidence.debug-enabled}。默认 false。 */
    private boolean debugEnabled = false;
}
