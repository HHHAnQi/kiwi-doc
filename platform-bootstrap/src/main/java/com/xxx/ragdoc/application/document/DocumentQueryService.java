package com.xxx.ragdoc.application.document;

import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.query.DocumentDetail;
import com.xxx.ragdoc.application.document.query.DocumentSummary;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.common.exception.NotFoundException;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 文档查询用例(读路径:列表 + 详情)。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentQueryService {

    private final DocumentRepository documentRepository;

    /** 分页查询(可选 status + 关键字)。 */
    @Transactional(readOnly = true)
    public Page<DocumentSummary> list(DocumentStatus status, String keyword, Pageable pageable) {
        return documentRepository.list(status, keyword, pageable);
    }

    /** 详情(含 chunk_count)。 不存在 → DOC_NOT_FOUND。 */
    @Transactional(readOnly = true)
    public DocumentDetail getDetail(Long id) {
        return documentRepository
                .findDetailById(id)
                .orElseThrow(() -> new NotFoundException(ErrorCode.DOC_NOT_FOUND, "文档不存在: " + id));
    }
}
