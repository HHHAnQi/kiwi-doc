package com.xxx.ragdoc.infrastructure.milvus;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Milvus 客户端 Bean 配置。 */
@Configuration
public class MilvusClientConfig {

    @Bean(destroyMethod = "close")
    public MilvusServiceClient milvusServiceClient(MilvusProperties props) {
        ConnectParam connect =
                ConnectParam.newBuilder()
                        .withHost(props.getHost())
                        .withPort(props.getPort())
                        .build();
        return new MilvusServiceClient(connect);
    }
}
