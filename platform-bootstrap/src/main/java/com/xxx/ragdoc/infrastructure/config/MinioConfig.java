package com.xxx.ragdoc.infrastructure.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 配置。
 *
 * <p>关键修复(本次烟测发现): 之前 {@code init()} 直接调 {@code minioClient()} 方法, Spring 在 @Configuration 类(@Bean
 * 方法)内部直接自调 method 会触发循环依赖。 修复: 把 bucket 初始化拆为独立 Component(MinioBucketInitializer)接受注入的 client。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "minio")
public class MinioConfig {

    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucket;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }

    @Bean("minioBucket")
    public String minioBucket() {
        return bucket;
    }

    /** 应用启动后创建 bucket(若不存在)。 不再用 @PostConstruct 自调 minioClient(), 避免循环依赖。 */
    @PostConstruct
    public void initBucket() {
        try {
            // 直接用 endpoint/credentials 新建一个临时 client 做初始化操作
            // (不用 Spring 管理的 Bean, 避免 @Configuration 自调 method 的代理冲突)
            MinioClient client =
                    MinioClient.builder()
                            .endpoint(endpoint)
                            .credentials(accessKey, secretKey)
                            .build();
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("✓ MinIO bucket '{}' 已自动创建", bucket);
            } else {
                log.info("✓ MinIO bucket '{}' 已存在", bucket);
            }
        } catch (Exception e) {
            log.warn("MinIO bucket 初始化失败(应用仍可启动): {}", e.getMessage());
        }
    }

    // ===== getter / setter(ConfigurationProperties 需要) =====

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }
}
