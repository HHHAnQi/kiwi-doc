package com.xxx.ragdoc.infrastructure.persistence.minio;

import com.xxx.ragdoc.application.document.port.FileStorage;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** FileStorage 的 MinIO 实现。 由 application 层通过接口注入,保持领域不依赖具体存储选型。 */
@Slf4j
@Component
public class MinioFileStorage implements FileStorage {

    private final MinioClient minioClient;
    private final String bucket;

    public MinioFileStorage(MinioClient minioClient, @Qualifier("minioBucket") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    @Override
    public String uploadRaw(Long docId, String filename, byte[] content) throws Exception {
        String objectKey = "raw/" + docId + "/" + sanitize(filename);
        minioClient.putObject(
                PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(
                                new ByteArrayInputStream(content), content.length, -1)
                        .build());
        log.debug("已上传 raw 文件: bucket={}, key={}, size={}", bucket, objectKey, content.length);
        return objectKey;
    }

    @Override
    public byte[] download(String objectKey) throws Exception {
        try (InputStream is =
                minioClient.getObject(
                        GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            return is.readAllBytes();
        }
    }

    /** 简单清洗文件名,防目录穿越与非法字符(MinIO 已隔离 key,但保留应用层校验)。 */
    private static String sanitize(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unnamed";
        }
        return filename.replaceAll("[\\\\/]+", "_");
    }
}
