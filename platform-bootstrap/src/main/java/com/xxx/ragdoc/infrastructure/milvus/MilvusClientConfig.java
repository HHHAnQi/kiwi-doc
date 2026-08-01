package com.xxx.ragdoc.infrastructure.milvus;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 客户端 Bean 配置(v2 API)。
 *
 * <p>SDK 2.5.x 切到 {@code io.milvus.v2.client.MilvusClientV2}, 旧的 {@code MilvusServiceClient} 只用于保留
 * v1 兼容代码; 新代码 (BM25 Function / hybridSearch) 必须用 v2 客户端。
 */
@Configuration
public class MilvusClientConfig {

    @Bean(destroyMethod = "close")
    public MilvusClientV2 milvusClientV2(MilvusProperties props) {
        ConnectConfig connect =
                ConnectConfig.builder()
                        .uri("http://" + props.getHost() + ":" + props.getPort())
                        .build();
        return new MilvusClientV2(connect);
    }
}
