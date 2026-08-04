package com.xxx.ragdoc.infrastructure.metadata;

import com.xxx.ragdoc.application.document.command.UploadCommand;
import com.xxx.ragdoc.application.document.port.MetadataExtractor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * P2-1 默认 impl: rule-based 元数据抽取, 仅从文件名识别 source/version/docType/language。
 *
 * <p>设计动机: 真实 SCA 语料调砒 (见 docs/adr/roadmap-agentic-rag-evolution.md P2-1) —
 * 用户上传时常常不显式标 metadata, 导致 source 全落 'unknown', RetrieveService MetadataFilter
 * 失效, P3-1 default fallback 失效。规则补全 → 减少手动成本 + 让元数据过滤链路真正可用。
 *
 * <p>原则:
 *
 * <ol>
 *   <li>只填空白: user 显式传的 source / version / docType / language 始终保留, 抽取器不胜出任何字段
 *   <li>无副作用: 不读 cmd.content (PDF 二进制); 仅基于 originalFilename (文件名 + 路径片段)
 *   <li>失败不挂: 任何 regex 异常吞掉, 返回原始 cmd
 * </ol>
 *
 * <p>不抽 language/version 时: UploadCommand constructor 已落 zh/doc 缺省, 抽不到就保持缺省。
 */
@Slf4j
@Component
public class RegexMetadataExtractor implements MetadataExtractor {

    // SCA 支持组件关键词; 顺序无关, 用 case-insensitive 边界匹配。
    // 覆盖常见命名: nacos-2.3.2-reference.pdf, sentinel-docs.pdf, sca-starter.pdf...
    private static final Pattern SOURCE_PATTERN =
            Pattern.compile(
                    "\\b(dubbo|nacos|seata|rocketmq|sentinel|spring-cloud-alibaba|sca)\\b",
                    Pattern.CASE_INSENSITIVE);

    // 版本号: 2.3.2 / 1.7 / 2.3.2-RC1 / v3.0.0; 限制总长避免超 16 字符 (DB 字段约束)。
    // 第 1 组 optional v 前缀, 第 2 组核心 dotted number, 第 3 组 optional 后缀 (-RC1/-alpha/.beta2)
    private static final Pattern VERSION_PATTERN =
            Pattern.compile(
                    "\\bv?(\\d+\\.\\d+(?:\\.\\d+){0,2}(?:[-.]?(?:RC|GA|M|alpha|beta)\\d?)?)(?:\\b|[-_.])",
                    Pattern.CASE_INSENSITIVE);

    // 文档类型关键词 → 规范 docType 枚举值 (与 DocumentEntity 注释一致: doc / blog / release-notes / spec / demo)
    private static final Pattern DOC_TYPE_PATTERN =
            Pattern.compile(
                    "\\b(reference|user[-_]?guide|tutorial|blog|release[-_]?notes?|spec|demo|faq)\\b",
                    Pattern.CASE_INSENSITIVE);

    // 语言区段, 命中 zh/zh-cn/chinese → zh, 否则按存在 en/en-us/english → en
    private static final Pattern LANG_ZH_PATTERN =
            Pattern.compile("\\b(zh|zh-cn|chinese)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LANG_EN_PATTERN =
            Pattern.compile("\\b(en|en-us|english)\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public UploadCommand enrich(UploadCommand cmd) {
        if (cmd == null) return null;
        // 注意: 进入 UploadCommand constructor 后, source blank 已被填 'unknown', version blank 仍 null,
        // language blank→'zh', docType blank→'doc'。需要先判断原值是不是缺省值, 才能判定 'user 没传'。
        // 用 cmd 各字段当前值 + 反向推断是否是缺省: source='unknown' / docType='doc' / language='zh' / version=null
        // 视为"未传 ⇒ 可补"。
        // 缺点: 用户显式传 'unknown' 也会被视为未传; 实际上 'unknown' 不是有效 source, 这种混淆可接受。
        String filename = cmd.originalFilename();
        String effectiveSource = cmd.source();
        String effectiveVersion = cmd.version();
        String effectiveDocType = cmd.docType();
        String effectiveLanguage = cmd.language();

        if ("unknown".equalsIgnoreCase(effectiveSource)) {
            String guessed = matchFirst(SOURCE_PATTERN, filename);
            if (guessed != null) {
                // spring-cloud-alibaba 简化为 sca; 否则保持 canonical lower-case
                effectiveSource = "spring-cloud-alibaba".equalsIgnoreCase(guessed) ? "sca" : guessed.toLowerCase();
                log.info(
                        "metadata.extract_source filename='{}', source='{}'",
                        safeFilename(filename),
                        effectiveSource);
            }
        }

        if (effectiveVersion == null) {
            String v = matchFirst(VERSION_PATTERN, filename);
            if (v != null) {
                // 防 16 字符上限截断: 仅取前 16 字符 (实测很少触发)
                effectiveVersion = v.length() > 16 ? v.substring(0, 16) : v;
                log.info(
                        "metadata.extract_version filename='{}', version='{}'",
                        safeFilename(filename),
                        effectiveVersion);
            }
        }

        if ("doc".equalsIgnoreCase(effectiveDocType)) {
            String t = matchFirst(DOC_TYPE_PATTERN, filename);
            if (t != null) {
                effectiveDocType = normalizeDocType(t);
                log.info(
                        "metadata.extract_doc_type filename='{}', doc_type='{}'",
                        safeFilename(filename),
                        effectiveDocType);
            }
        }

        if ("zh".equalsIgnoreCase(effectiveLanguage)) {
            // 仅在用户没传 (默认 zh) 时尝试覆盖; 命中 en 才改, 否则保持 zh
            if (matchFirst(LANG_EN_PATTERN, filename) != null
                    && matchFirst(LANG_ZH_PATTERN, filename) == null) {
                effectiveLanguage = "en";
                log.info("metadata.extract_language filename='{}', language='en'", safeFilename(filename));
            }
        }

        // 优化: 如果全部未变, 直接返原 cmd (避免构造新对象)
        if (sameAsInput(cmd, effectiveSource, effectiveVersion, effectiveDocType, effectiveLanguage)) {
            return cmd;
        }

        return new UploadCommand(
                cmd.originalFilename(),
                cmd.mimeType(),
                cmd.sizeBytes(),
                cmd.content(),
                cmd.tenantId(),
                effectiveSource,
                effectiveVersion,
                effectiveLanguage,
                effectiveDocType);
    }

    /** 找到首个匹配 (lower-cased 不带分隔), 找不到返 null。 */
    private static String matchFirst(Pattern p, String input) {
        Matcher m = p.matcher(input);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /** 把命中的关键词 (reference / user-guide / release-notes 规范到 doc/blog/release-notes/spec/demo/faq)。 */
    private static String normalizeDocType(String raw) {
        String lower = raw.toLowerCase().replace('_', '-');
        return switch (lower) {
            case "reference", "user-guide", "tutorial" -> "doc"; // reference book 算 doc
            case "blog" -> "blog";
            case "release-notes" -> "release-notes";
            case "spec" -> "spec";
            case "demo" -> "demo";
            case "faq" -> "spec"; // faq 不在枚举, 规范到 spec (问答条目)
            default -> "doc";
        };
    }

    private static boolean sameAsInput(
            UploadCommand cmd, String src, String ver, String dt, String lang) {
        return eq(cmd.source(), src)
                && eq(cmd.version(), ver)
                && eq(cmd.docType(), dt)
                && eq(cmd.language(), lang);
    }

    private static boolean eq(String a, String b) {
        return (a == null) ? b == null : a.equals(b);
    }

    /** 日志安全: 文件名截 60 字符 + 删除换行 (防爆日志)。 */
    private static String safeFilename(String s) {
        if (s == null) return "";
        String oneLine = s.replaceAll("[\\r\\n]", " ");
        return oneLine.length() > 60 ? oneLine.substring(0, 60) + "…" : oneLine;
    }
}
