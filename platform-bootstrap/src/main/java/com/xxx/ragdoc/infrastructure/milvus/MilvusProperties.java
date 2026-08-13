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

    /** schema 不匹配时是否阻止启动。生产建议 true；false 只降级告警，绝不自动删除数据。 */
    private boolean failOnSchemaMismatch = true;
}
