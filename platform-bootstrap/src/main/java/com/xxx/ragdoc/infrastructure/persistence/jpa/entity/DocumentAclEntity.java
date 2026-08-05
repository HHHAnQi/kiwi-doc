package com.xxx.ragdoc.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * document_acl 表 Entity (V9 RAG-Perm-001)。
 *
 * <p>多对多授权: USER/ROLE/TENANT × READ/WRITE/OWNER。求用户可读 docId 集合时, 把它的 user_id +
 * 所有 role + tenant_id 分别查 principal_id, 求并集去重。
 */
@Entity
@Table(
        name = "document_acl",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_acl_doc_principal_perm",
                        columnNames = {"document_id", "principal_type", "principal_id", "perm"}))
public class DocumentAclEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /** USER / ROLE / TENANT */
    @Column(name = "principal_type", nullable = false, length = 16)
    private String principalType;

    /** principal_type 解析后的 id: user_id / role:xxx / tenant_id */
    @Column(name = "principal_id", nullable = false, length = 64)
    private String principalId;

    /** READ / WRITE / OWNER */
    @Column(name = "perm", nullable = false, length = 16)
    private String perm;

    @Column(name = "granted_by", length = 64)
    private String grantedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // ===== getters / setters =====

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

    public String getPrincipalType() {
        return principalType;
    }

    public void setPrincipalType(String principalType) {
        this.principalType = principalType;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public void setPrincipalId(String principalId) {
        this.principalId = principalId;
    }

    public String getPerm() {
        return perm;
    }

    public void setPerm(String perm) {
        this.perm = perm;
    }

    public String getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(String grantedBy) {
        this.grantedBy = grantedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
