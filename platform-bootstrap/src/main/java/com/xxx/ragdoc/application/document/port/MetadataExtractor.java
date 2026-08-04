package com.xxx.ragdoc.application.document.port;

import com.xxx.ragdoc.application.document.command.UploadCommand;

/**
 * P2-1: 上传元数据自动抽取端口 (rule-based)。
 *
 * <p>设计目标: 减少手动表单输入。用户未显式传 source/version/docType 时, 用文件名规则 (regex) 兜底猜测。
 *
 * <p>策略: <b>仅填空白, 不覆盖用户输入</b>。user 显式传的字段保留; 缺失的用规则补。让手动 override 始终优先。
 *
 * <p>规则集 (基于 SCA 真实语料调研):
 *
 * <ul>
 *   <li>source: 文件名 / 路径中出现的关键词 → dubbo / nacos / seata / rocketmq / sentinel / sca
 *   <li>version: dotted 数字版本 (2.3.2 / 1.7) 截短到 16 字符
 *   <li>docType: 文件名中是否出现 reference / user-guide / blog / release-notes / spec / demo
 *   <li>language: 路径含 zh / en
 * </ul>
 *
 * <p>实现侧约束:
 *
 * <ul>
 *   <li>不抛异常: 抽取失败返回原始 cmd (透传), 不影响主流程
 *   <li>线程安全: 无状态, 仅依赖入参
 *   <li>幂等: 多次调用同入参返同出参
 * </ul>
 */
public interface MetadataExtractor {

    /**
     * 入参 cmd 返回一个副本: 仅把 cmd 里 null/blank 的字段按规则补全, 非空字段原样保留。
     *
     * @param cmd 原始上传命令 (含 originalFilename / 用户显式填的 metadata)
     * @return 补全后的 cmd; 实现必须保证不修改入参
     */
    UploadCommand enrich(UploadCommand cmd);
}
