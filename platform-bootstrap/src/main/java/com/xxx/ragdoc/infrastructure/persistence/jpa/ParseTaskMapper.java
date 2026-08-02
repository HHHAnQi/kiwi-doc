package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.domain.document.ParseTask;
import com.xxx.ragdoc.domain.document.ParseTaskStatus;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.ParseTaskEntity;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ParseTask domain ↔ ParseTaskEntity 双向翻译。
 *
 * <p>attempts 字段(domain 是 {@code List<ParseTask.Attempt>}, entity 是 JSON 字符串)走 Jackson 序列化。
 * 解析失败不抛, 返回空列表 + log warn —— attempts 是诊断辅助字段, 不该挡状态迁移。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParseTaskMapper {

    private static final TypeReference<List<ParseTask.Attempt>> ATTEMPTS_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public ParseTask toDomain(ParseTaskEntity e) {
        if (e == null) return null;
        return new ParseTask(
                e.getId(),
                e.getDocumentId(),
                e.getContentHash(),
                ParseTaskStatus.valueOf(e.getStatus()),
                e.getRetryCount(),
                e.getMaxRetries(),
                e.getChunksWritten(),
                e.getChunkSeqOffset(),
                e.getErrorMessage(),
                e.getErrorClass(),
                decodeAttempts(e.getAttempts()),
                e.getVisibleAt(),
                e.getLeasedBy(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }

    public ParseTaskEntity toEntity(ParseTask d) {
        ParseTaskEntity e = new ParseTaskEntity();
        e.setId(d.id());
        e.setDocumentId(d.documentId());
        e.setContentHash(d.contentHash());
        e.setStatus(d.status().name());
        e.setRetryCount(d.retryCount());
        e.setMaxRetries(d.maxRetries());
        e.setChunksWritten(d.chunksWritten());
        e.setChunkSeqOffset(d.chunkSeqOffset());
        e.setErrorMessage(d.errorMessage());
        e.setErrorClass(d.errorClass());
        e.setAttempts(encodeAttempts(d.attempts()));
        e.setVisibleAt(d.visibleAt());
        e.setLeasedBy(d.leasedBy());
        e.setCreatedAt(d.createdAt());
        e.setUpdatedAt(d.updatedAt());
        return e;
    }

    private List<ParseTask.Attempt> decodeAttempts(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, ATTEMPTS_TYPE);
        } catch (Exception ex) {
            log.warn("parse_task.attempts_decode_failed json={} err={}", json, ex.getMessage());
            return Collections.emptyList();
        }
    }

    private String encodeAttempts(List<ParseTask.Attempt> attempts) {
        if (attempts == null || attempts.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(attempts);
        } catch (Exception ex) {
            log.warn("parse_task.attempts_encode_failed err={}", ex.getMessage());
            return null;
        }
    }
}
