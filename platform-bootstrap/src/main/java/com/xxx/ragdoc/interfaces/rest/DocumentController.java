package com.xxx.ragdoc.interfaces.rest;

import com.xxx.ragdoc.application.document.DocumentUploadService;
import com.xxx.ragdoc.application.document.command.UploadCommand;
import com.xxx.ragdoc.application.document.command.UploadResult;
import com.xxx.ragdoc.interfaces.rest.dto.DocumentUploadResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 文档上传 REST 接口。仅注入应用服务 + DTO 映射,不持有业务逻辑。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Tag(name = "Document", description = "文档上传/管理")
public class DocumentController {

    private final DocumentUploadService uploadService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文档", description = "支持 PDF/Markdown/HTML,单文件 ≤ 50MB")
    public ResponseEntity<DocumentUploadResponse> upload(
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        log.info("rest.document.upload.start filename={}, size={}, trace_id={}",
                file.getOriginalFilename(),
                file.getSize(),
                MDC.get("trace_id"));

        UploadCommand cmd = new UploadCommand(
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                file.getBytes(),
                "default" // V4 替换为 JWT tenant
        );

        UploadResult result = uploadService.upload(cmd);

        // 幂等命中 → 200;新建 → 201
        ResponseEntity.BodyBuilder builder = result.idempotentHit()
                ? ResponseEntity.ok()
                : ResponseEntity.created(null);
        return builder.body(DocumentUploadResponse.from(result));
    }
}
