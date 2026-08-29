# GITHUB_METADATA_UPDATE — Owner Action 清单

> 2026-08-29 · 本机无 `gh` CLI 与 GitHub token，以下元数据修改需 Owner 在仓库 Web UI 完成。

## 1. Repository Description（Settings → General → Description）

推荐值：

```text
A production-oriented RAG platform with hybrid retrieval, durable ingestion, grounded generation, and systematic evaluation.
```

## 2. Topics（Settings → General → Topics）

推荐（全部真实支持）：

```text
rag  llm  retrieval  hybrid-search  vector-search  milvus  rag-evaluation  agentic-rag  spring-boot
```

禁止添加（当前未实现，防 overclaim）：

```text
multimodal-rag  high-availability  production-ready
```

## 3. About 区域 Website（可选）

可留空或指向 `docs/architecture/architecture-diagrams.md` 的 GitHub 渲染页。

## 4. 默认分支可见性确认

合并 PR 后确认 `main` 为 Default branch（当前即默认，无需改动），并确认首页显示的是
新版 KiwiRAG README（首行 `# 🥝 KiwiRAG`）。

## 5. Merge PR

如 GitHub 因权限要求手动确认 Merge（本机已推送分支并尝试创建 PR），
PR 标题：`release: harden KiwiRAG portfolio v1`。
