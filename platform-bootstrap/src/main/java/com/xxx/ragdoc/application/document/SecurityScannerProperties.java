package com.xxx.ragdoc.application.document;

import com.xxx.ragdoc.application.document.security.ScanResult;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Task 8: Document Security Scanner 配置 (防 prompt injection)。
 *
 * <p>沿用 {@code RerankProperties} / {@code CitationVerifierProperties} 模式 (enabled 主开关 + on-fail
 * policy)。默认全部关闭, 改 {@code RAG_SECURITY_SCANNER_ENABLED=true} 开启。
 *
 * <p>任务文档要求 "检测: Ignore previous / system prompt / tool calling injection" → 实现侧
 * RegexSecurityScanner 用正则规则覆盖这三类 + role_hijack + encoding_obfuscation。
 *
 * <p>行为选型:
 *
 * <ul>
 *   <li>{@code BLOCK_ON_MALICIOUS=true} (默认): MALICIOUS 标记的文档直接 markFailed, 不进 chunk
 *   <li>{@code BLOCK_ON_MALICIOUS=false}: 仅 TAG, 文档仍进 chunk (灰度/观察模式)
 * </ul>
 *
 * <p>SUSPICIOUS 永远不阻断, 仅 log + summary (供 ops 监控误报率)。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.security-scanner")
public class SecurityScannerProperties {

    /** 企业入库默认开启；仅允许通过显式环境配置灰度关闭。 */
    private boolean enabled = true;

    /** MALICIOUS 时是否阻断上传 (markFailed + 不 chunk)。 false 时仅 TAG, 文档仍进 chunk (灰度模式)。 */
    private boolean blockOnMalicious = true;

    /** 触发 MALICIOUS 的命中数阈值 (多模式共振判MALICIOUS); 小于该值判 SUSPICIOUS。default 3。 */
    private int maliciousThreshold = 3;

    /** 返回概要 (方便测试用)。 */
    public boolean shouldBlock(ScanResult r) {
        return enabled
                && blockOnMalicious
                && r != null
                && r.outcome() == ScanResult.Outcome.MALICIOUS;
    }
}
