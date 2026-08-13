package com.xxx.ragdoc.application.chat.harness;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * PR-5: Fixture Metadata (一定写入 Fixture 顶层 metadata 字段)。
 *
 * <p>不依赖系统当前时间判断 Fixture 是否有效 (EMS-PR5 §8.3); {@link #recordedAt} 仅记录录制时刻, REPLAY 用
 * schema/request/index/scope 等 "结构性" 字段做 validity 判定。
 */
public record FixtureMetadata(
        String recordedAt,
        String requestSchemaVersion,
        String responseSchemaVersion,
        String hashAlgorithm,
        String permissionScopeVersion,
        String indexVersion,
        String datasetVersion,
        String sourceMode,
        JsonNode harnessConfigSnapshot) {

    public FixtureMetadata {
        if (hashAlgorithm == null || hashAlgorithm.isBlank()) {
            hashAlgorithm = "SHA-256";
        }
        if (recordedAt == null) recordedAt = "";
        if (requestSchemaVersion == null) requestSchemaVersion = "";
        if (responseSchemaVersion == null) responseSchemaVersion = "";
        if (permissionScopeVersion == null) permissionScopeVersion = "";
        if (indexVersion == null) indexVersion = "";
        if (datasetVersion == null) datasetVersion = "";
        if (sourceMode == null) sourceMode = "";
    }
}
