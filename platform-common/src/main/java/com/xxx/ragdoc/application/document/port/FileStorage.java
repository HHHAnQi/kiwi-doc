package com.xxx.ragdoc.application.document.port;

/**
 * 文件存储抽象(domain/application 层只认此接口,不关心具体是 MinIO/S3/Local)。 详见 docs/architecture/domain-model.md §6
 * 与 ADR 抽象层决策。
 */
public interface FileStorage {

    /**
     * 上传原始文件,key 规则: raw/{doc_id}/{filename}。
     *
     * @param docId 所属 Document ID(若尚未持久化则可传 hash 前缀,V1 假设已有 id)
     * @param filename 原始文件名
     * @param content 内容字节
     * @return 存储用的 object_key(用于 file_objects 表)
     */
    String uploadRaw(Long docId, String filename, byte[] content) throws Exception;

    /** 下载文件字节(供 parser 读取)。 */
    byte[] download(String objectKey) throws Exception;
}
