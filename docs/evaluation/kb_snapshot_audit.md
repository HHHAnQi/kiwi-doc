# KB Snapshot Audit (PR-7f.2c.1.5 Task 1)

> Scope: actual storage code for `documents`, `chunks`, Milvus vector store, `Evidence`
> runtime type, KB export tooling, and PII guardrails.
>
> Source of truth: Java code at `platform-bootstrap/src/main/java/com/xxx/ragdoc/`
> and `platform-common/src/main/java/.../application/chat/evidence/Evidence.java`.
> Read-only audit. No code modified. No experiments run.

**Bottom line / actionability**: An offline KB snapshot that lets annotators compute
`contentHash` and `evidenceId` is **buildable** from MySQL alone (Milvus fields are
projections of MySQL facts). The snapshot path requires:
`(tenant_id, document_id, chunk_id, document_version, content)` per row.
The hash derivation rules discovered here drive `schema.json`,
`validate_kb_snapshot.py`, and `gold_annotation_helper.py`.

---

## 1. Current source of KB

Production grade, three coexisting stores:

| Store | Role | Authority |
|---|---|---|
| MySQL `documents` | Document metadata + version + tenant + ACL | canonical |
| MySQL `chunks` | Chunk content + content_hash + parent/child + section_path | canonical |
| Milvus `ragdoc_chunks` (configurable name) | Vector + sparse BM25 + denormalized metadata | projection of MySQL |

The validator **must read from MySQL**. Milvus does NOT carry `content_hash` or `content`
beyond a 4000-char `text` field (`MilvusCollectionInitializer.java:162-164`). The only
content-bearing store that supports the full bytes needed for SHA-256 is MySQL.

### Join path

```
chunks.document_id  →  documents.id       (1 doc has N chunks)
documents.tenant_id                      (chunks row has NO tenant_id column)
documents.version                        (chunks row has NO version column)
```

MySQL-to-Milvus join key is `Milvus.chunk_id  →  chunks.id` (Milvus PK autoID is unusable).

---

## 2. Required fields for snapshot

### 2.1 From `documents` table (`DocumentEntity.java`)

| Field (snapshot needs) | MySQL column | Java type | Notes |
|---|---|---|---|
| `tenantId` | `tenant_id` | `String(32)` | default `"default"`. **filter field** for tenant isolation |
| `documentId` | `id` | `Long` | PK |
| `originalFilename` | `original_filename` | `String(255)` | nice-to-have for human inspection |
| `source` | `source` | `String(32)` | `dubbo/nacos/seata/rocketmq/sentinel/unknown` |
| `version` | `version` | `String(16)` | **nullable** — handle null → `"unknown"` |
| `language` | `language` | `String(8)` | default `"zh"` |
| `docType` | `doc_type` | `String(16)` | `doc`, etc. |
| `visibility` | `visibility` | `String(16)` | `PRIVATE` / `TENANT` / `PUBLIC` |
| `status` | `status` | `String(16)` | state machine, only `READY` rows are evaluable |
| `deletedAt` | `deleted_at` | `Instant?` | **soft-delete** — `WHERE deleted_at IS NULL` required |
| `isDefault` | `is_default` | `Boolean` | snapshot should honor same-source default version flag |

### 2.2 From `chunks` table (`ChunkEntity.java`)

| Field (snapshot needs) | MySQL column | Java type | Notes |
|---|---|---|---|
| `chunkId` | `id` | `Long` | PK |
| `documentId` | `document_id` | `Long` | FK to documents.id |
| `seq` | `seq` | `Integer` | sequence within doc + chunk_type |
| `chunkType` | `chunk_type` | `String(16)` | `TEXT` / `PARENT` / `CHILD` |
| `content` | `content` | `MEDIUMTEXT` | **not nullable** — the bytes we hash |
| `page` | `page` | `Integer` | |
| `bbox` | `bbox` | `String (JSON)` | optional |
| `parentChunkId` | `parent_chunk_id` | `Long?` | parent/child hierarchical chunks |
| `contentHash` | `content_hash` | `String(64)` | **not nullable** — snapshot must verify against recomputed SHA-256 |
| `sectionPath` | `section_path` | `String (JSON)` | e.g. `["Dubbo","异步调用"]` — useful for `rationale` field in gold annotation |
| `createdAt` | `created_at` | `Instant` | |

Uniqueness: `UNIQUE INDEX uk_doc_seq_type (document_id, seq, chunk_type)`.

---

## 3. Evidence runtime type — hash derivation

**Source**: `platform-common/src/main/java/com/xxx/ragdoc/application/chat/evidence/Evidence.java`.

### 3.1 `Evidence.of(...)` factory (L46-78)

```java
String safeContent = content == null ? "" : content;
String contentHash = sha256(safeContent);                                    // L64
String evidenceId  = sha256(tenantId + "|" + documentId + "|" + chunkId + "|" + contentHash);  // L65
```

### 3.2 Hash format — **CRITICAL correction**

| Field | Format | Source of truth |
|---|---|---|
| `contentHash` | **64-char lowercase hex** (full SHA-256, NO trunction) | `Evidence.sha256()` L85-93: `HexFormat.of().formatHex(digest)` |
| `evidenceId`  | **64-char lowercase hex** (full SHA-256 of `tenant\|doc\|chunk\|contentHash`) | L65, same `sha256()` helper |
| Delimiter | pipe `\|` literal | L65 string concat |
| Null content handling | `""` substituted | L63 |

> ⚠️ `Evidence.evidenceId` is **NOT** truncated to 12 chars. The grep-quoted `[:12]`
> pattern seen in other code paths (e.g. `ToolExecutor.java:322, 414`) is for *other*
> keys, NOT the evidence ID. The earlier `validate_gold_dataset.py` regex
> `^[a-f0-9]{12}$` is too strict and must be relaxed to `^[a-f0-9]{64}$`. (Fixed in
> Task 5 follow-up.)

### 3.3 Algorithm impl

```java
private static String sha256(String s) {
    try {
        var md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);   // 64 lowercase hex
    } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
}
```

Python equivalent (used in `gold_annotation_helper.py`):
```python
import hashlib
def sha256_hex(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()  # 64 lowercase hex
def content_hash_of(content: str) -> str:
    return sha256_hex(content if content is not None else "")
def evidence_id_of(tenant_id: str, doc_id: int, chunk_id: int, content_hash: str) -> str:
    return sha256_hex(f"{tenant_id}|{doc_id}|{chunk_id}|{content_hash}")
```

### 3.4 Pre-existing chunks.content_hash agreement

Algorithm at parsing time `TikaParsingTrigger.sha256Hex` (L405) and runtime
`Evidence.sha256` (L85-93) are byte-equivalent SHA-256 → lowercase hex. So:

```
chunks.content_hash == Evidence.of(...).contentHash
  ⟺  bytes passed to Evidence.of == bytes in chunks.content
```

This is the invariant the validator enforces.

---

## 4. Export method

### 4.1 No existing snapshotter

Sub-tree search for `*Exporter*`, `*Dump*`, `*Snapshot*`, `pg_dump`, `mysqldump` in
`scripts/`, `eval/`, `deploy/`, `docs/operations/`: **zero matches** that pull chunks en
masse.

Closest existing tools are narrower:
- `scripts/reindex_milvus.py:91` — `SELECT id, document_id, seq, page, content FROM chunks`
  (no content_hash, no tenant_id, no version — reindex-only minimal projection).
- `eval/corpus_coverage_audit.py:51` — `SELECT DISTINCT id FROM chunks WHERE id IN (...)`
  (presence check only, no content).

### 4.2 Export SQL (this PR's recommended query, for the helper script)

```sql
SELECT
    d.tenant_id          AS tenant_id,
    c.document_id        AS document_id,
    c.id                 AS chunk_id,
    COALESCE(d.version, 'unknown') AS document_version,
    c.chunk_type         AS chunk_type,
    c.seq                AS seq,
    c.page               AS page,
    c.content            AS content,
    c.content_hash       AS content_hash_db,
    c.section_path       AS section_path,
    c.parent_chunk_id    AS parent_chunk_id,
    d.source             AS source,
    d.language           AS language,
    d.visibility         AS visibility
FROM chunks c
JOIN documents d ON d.id = c.document_id
WHERE d.deleted_at IS NULL
  AND d.status = 'READY'
  AND d.tenant_id = :tenant_id          -- mandatory tenant filter
ORDER BY c.document_id, c.seq, c.chunk_type;
```

This single query produces the snapshot. Recommended output is NDJSON (`one chunk per line`).

---

## 5. Privacy / PII considerations

### 5.1 PII scanner

**There is NO PII scanner.** The repo's only content-scan primitive is
`RegexSecurityScanner` (`infrastructure/security/RegexSecurityScanner.java`), which is
**prompt-injection only**:
- Threat categories at L65-109: `IGNORE_PREVIOUS`, `SYSTEM_PROMPT_LEAK`,
  `TOOL_CALLING`, `ROLE_HIJACK`, `ENCODING_OBFUSCATION`.
- Output: `CLEAN` / `SUSPICIOUS` / `MALICIOUS` verdict, **no redaction**.
- Gates ingest at `TikaParsingTrigger.java:122-145` in BLOCK mode.

Consequence: `chunks.content` is **verbatim** parsed text from the source document.
A snapshot shared externally MUST apply its own PII sweep — the platform does not
guarantee content is PII-free.

### 5.2 Tenant isolation fields (snapshot MUST honor)

| Field | Purpose | Default |
|---|---|---|
| `documents.tenant_id` | tenant partition | `"default"` |
| `documents.visibility` | `PRIVATE` / `TENANT` / `PUBLIC` | `"TENANT"` |
| `documents.owner_id` | uploader | nullable (system/legacy) |
| `documents_acl` (table) | fine-grained ACL rows; cols `principal_type` USER/ROLE/TENANT, `principal_id`, `perm` READ/WRITE/OWNER | — |

Snapshot filter policy recommendation:
- Always include `tenant_id` in WHERE clause.
- For internal eval workspace, default to `tenant_id = "default"` AND `visibility != "PRIVATE"`.
- For external sharing, additionally filter `visibility = "PUBLIC"` AND require manual review.

### 5.3 Soft delete rule

`documents.deleted_at IS NULL` is mandatory. Without it, tombstoned docs leak into
the snapshot and gold annotation gets anchored to deleted content.

### 5.4 Snapshot reuse + version drift

- `documents.version` is nullable. Annotators bind gold to whatever version the DB
  shows today. Any future corpus re-embedding changes `chunks.content_hash`, breaking
  annotation. Mitigation: pin snapshot by a `snapshotId = sha256(all content_hash +
  version)`, recorded per evaluation run.
- `documents.is_default` flags the same-source default version. For
  `semantic_metadata_combo` slice gold annotation, only default versions matter —
  explicit-version queries annotate against the non-default sibling.

---

## 6. Snapshot file layout recommendation

Header + per-chunk NDJSON; one file per tenant + snapshot date:

```
eval/agentic/kb_snapshot/snapshots/
  └── tenant-default-2026-08-08.ndjson
       # line 1: {"snapshotMeta": {...}, "snapshotId": "...", "exportedAt": "..."}
       # line 2+: {"documentId": ..., "chunkId": ..., "documentVersion": "...",
       #           "content": ..., "contentHash": ..., "metadata": {...}}
```

Validator + helper both consume any file matching `schema.json` from this dir.

This layout keeps the snapshot transportable (NDJSON is append-only, no DB
dependency at annotation time) and lets the validator confirm `snapshotId` matches
recomputed hashes.

---

## 7. Gaps that this snapshot does NOT fill

| Gap | Mitigation |
|---|---|
| No freshness check — if the corpus changes, snapshot becomes stale | Validator computes `snapshotId` and warns; PR-7f.2c.2 re-export per run |
| No PII redaction in upstream platform | Annotator must not paste PII into rationale; snapshot file access restricted to project members |
| No version pinning at MySQL level | `document_version` is nullable + editable post-ingest; the snapshot's `snapshotId` is the only durable anchor |
| Multi-tenant — Snapshot-of-all-tenants would mix ACLs | Helper forces `--tenant-id`; default rejects multi-tenant pulls |
| Milvus-only fields (vector, BM25 sparse) cannot be regenerated offline | Snapshot is MySQL-only; if vector-state matters, snapshot must add a parallel `milvus_state.json` (declined for this PR — assessment uses content + metadata only) |

---

## 8. Not fabricated

No experiments run. No metric values produced. All findings derived from static
code reading of `DocumentEntity.java`, `ChunkEntity.java`, `MilvusCollectionInitializer.java`,
`Evidence.java`, `TikaParsingTrigger.java`, `RegexSecurityScanner.java`, plus
`grep` of codebase for export scripting tools.
