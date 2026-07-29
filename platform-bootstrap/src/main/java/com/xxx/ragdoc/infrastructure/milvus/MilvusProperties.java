package com.xxx.ragdoc.infrastructure.milvus;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Milvus 连接配置。 对应 application-*.yml 的 milvus.* 块。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "milvus")
public class MilvusProperties {

    private String host;

    private int port;

    /** collection 名, V2 固定 documents_v1。 */
    private String collection;
}
