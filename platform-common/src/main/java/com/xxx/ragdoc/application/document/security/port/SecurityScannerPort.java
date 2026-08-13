package com.xxx.ragdoc.application.document.security.port;

import com.xxx.ragdoc.application.document.security.ScanResult;

/**
 * Task 8: Document Security Scanner 端口 — 解析管道在 chunk 前调它, 防止恶意 prompt injection 文档内容进入知识库后被 LLM
 * 当指令执行。
 *
 * <p>典型流程 (TikaParsingTrigger):
 *
 * <pre>
 *   1. byte[] → Tika → fullText (String)
 *   2. securityScanner.scan(fullText, docId) → ScanResult         ← Task 8 接入点
 *   3. 若 MALICIOUS → doc.markFailed("security_blocked"), 抛不进 chunk
 *   4. CLEAN/SUSPICIOUS → 继续 chunk → embed → Milvus
 * </pre>
 *
 * <p>实现 {@code infrastructure.security.RegexSecurityScanner} 用确定性规则 (正则/关键词) 判定, 不调 LLM (低成本, 防
 * LLM-as-judge 自身被越狱)。
 *
 * <p>失败侧约定: 实现侧异常由调用方 catch + markFailed, 不挂主流程。
 *
 * <p>Port 放 platform-common 跨模块复用 (platform-bootstrap sync 路径 + parser-service async 路径 都用同一接口)。
 */
public interface SecurityScannerPort {

    /**
     * 扫描文本查 prompt injection 模式。
     *
     * @param text 待扫描文本 (Tika 抽出的全文)
     * @param documentId 文档 id, 仅用于日志/范围; 不参与决策
     */
    ScanResult scan(String text, Long documentId);
}
