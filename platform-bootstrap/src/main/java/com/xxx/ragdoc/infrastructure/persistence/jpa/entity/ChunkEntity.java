package com.xxx.ragdoc.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** chunks 表 JPA Entity。 V1 parsing stub 不会写入, 此 Entity 仅为详情 chunk_count 统计用; V2 真实解析接入后用于切片读取。 */
@Entity
@Table(name = "chunks")
public class ChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "seq", nullable = false)
    private Integer seq;

    @Column(name = "chunk_type", nullable = false, length = 16)
    private String chunkType;

    @Column(name = "content", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "page", nullable = false)
    private Integer page;

    @Column(name = "bbox", columnDefinition = "JSON")
    private String bbox;

    @Column(name = "parent_chunk_id")
    private Long parentChunkId;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** Q3-B: chunk 所属 markdown heading 路径栈, JSON 数组字符串(如 ["Dubbo","异步调用"]); null = 无上下文。 */
    @Column(name = "section_path", columnDefinition = "JSON")
    private String sectionPath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getChunkType() {
        return chunkType;
    }

    public void setChunkType(String chunkType) {
        this.chunkType = chunkType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public String getBbox() {
        return bbox;
    }

    public void setBbox(String bbox) {
        this.bbox = bbox;
    }

    public Long getParentChunkId() {
        return parentChunkId;
    }

    public void setParentChunkId(Long parentChunkId) {
        this.parentChunkId = parentChunkId;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getSectionPath() {
        return sectionPath;
    }

    public void setSectionPath(String sectionPath) {
        this.sectionPath = sectionPath;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
