package com.xxx.ragdoc.interfaces.rest;

import com.xxx.ragdoc.application.document.DocumentManageService;
import com.xxx.ragdoc.application.document.DocumentQueryService;
import com.xxx.ragdoc.application.document.DocumentUploadService;
import com.xxx.ragdoc.application.document.command.UploadCommand;
import com.xxx.ragdoc.application.document.command.UploadResult;
import com.xxx.ragdoc.application.document.query.DocumentDetail;
import com.xxx.ragdoc.application.document.query.DocumentSummary;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import com.xxx.ragdoc.interfaces.rest.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Document REST 接口(上传 + 列表 + 详情 + 删除 + 重试)。 契约以 api-contracts.md §A/§B 为单一事实源。 */
@Slf4j
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Tag(name = "Document", description = "文档上传/管理")
public class DocumentController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentUploadService uploadService;
    private final DocumentQueryService queryService;
    private final DocumentManageService manageService;

    // ============================================================
    // 上传
    // ============================================================
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文档")
    public ResponseEntity<DocumentUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "version", required = false) String version,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "doc_type", required = false) String docType)
            throws IOException {
        log.info(
                "rest.document.upload.start filename={}, size={}, source={}, trace_id={}",
                file.getOriginalFilename(),
                file.getSize(),
                source,
                MDC.get("trace_id"));

        UploadCommand cmd =
                new UploadCommand(
                        file.getOriginalFilename(),
                        file.getContentType(),
                        file.getSize(),
                        file.getBytes(),
                        "default",
                        source,
                        version,
                        language,
                        docType);

        UploadResult result = uploadService.upload(cmd);

        ResponseEntity.BodyBuilder builder =
                result.idempotentHit() ? ResponseEntity.ok() : ResponseEntity.created(null);
        return builder.body(DocumentUploadResponse.from(result));
    }

    // ============================================================
    // 查询
    // ============================================================
    @GetMapping
    @Operation(summary = "文档列表")
    public PagedResponse<DocumentSummaryResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page) - 1;
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        DocumentStatus statusFilter = null;
        if (status != null && !status.isBlank()) {
            statusFilter = DocumentStatus.valueOf(status.toUpperCase());
        }

        Page<DocumentSummary> p =
                queryService.list(statusFilter, keyword, PageRequest.of(safePage, safeSize));

        List<DocumentSummaryResponse> items =
                p.getContent().stream().map(DocumentSummaryResponse::from).toList();
        return PagedResponse.of(items, p.getTotalElements(), page, safeSize);
    }

    @GetMapping("/{id}")
    @Operation(summary = "文档详情")
    public DocumentDetailResponse getDetail(@PathVariable Long id) {
        DocumentDetail d = queryService.getDetail(id);
        return DocumentDetailResponse.from(d);
    }

    // ============================================================
    // 管理
    // ============================================================
    @DeleteMapping("/{id}")
    @Operation(summary = "软删文档")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        manageService.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "重试失败文档(仅 FAILED 可重试一次)")
    public ResponseEntity<Void> retry(@PathVariable Long id) {
        manageService.retry(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/default")
    @Operation(summary = "设为同 source 的默认版本 (RetrieveService fallback)")
    public ResponseEntity<Void> setDefault(@PathVariable Long id) {
        manageService.setDefault(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/unarchive")
    @Operation(summary = "反归档: 复活软删文档并重新解析")
    public ResponseEntity<Void> unarchive(@PathVariable Long id) {
        manageService.unarchive(id);
        return ResponseEntity.accepted().build();
    }
}
