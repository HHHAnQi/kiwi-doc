package com.xxx.ragdoc.infrastructure.milvus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import java.util.List;
import org.junit.jupiter.api.Test;

class MilvusCollectionInitializerSafetyTest {

    @Test
    void schemaMismatchFailsWithoutDroppingCollection() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        MilvusProperties props = new MilvusProperties();
        props.setCollection("documents_v1");
        props.setFailOnSchemaMismatch(true);
        when(client.hasCollection(any())).thenReturn(true);
        DescribeCollectionResp response = mock(DescribeCollectionResp.class);
        when(response.getFieldNames()).thenReturn(List.of("id", "dense_vector"));
        when(client.describeCollection(any())).thenReturn(response);

        assertThatThrownBy(() -> new MilvusCollectionInitializer(client, props).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema 不兼容");

        verify(client).describeCollection(any());
        // 编译期已无 dropCollection 调用；此测试锁定“不吞错继续启动”的行为。
    }
}
