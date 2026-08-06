package com.xxx.ragdoc.application.chat.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.domain.shared.PipelineType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PR-2 / EMS-PR2: {@link ChatPipelineRegistry} 启动 fail-fast + 运行时 fail-closed。
 *
 * <p>对应 EMS-PR2 测试要求:
 *
 * <ul>
 *   <li>同 type 两个 bean 注册 → 启动抛 IllegalStateException
 *   <li>get(未注册 type) → DomainException PIPELINE_NOT_FOUND, 不静默回退到任意 pipeline
 * </ul>
 */
@DisplayName("ChatPipelineRegistry - PR-2 fail-fast / fail-closed")
class ChatPipelineRegistryTest {

    @Test
    @DisplayName("同 type 两个 bean → IllegalStateException 启动 fail-fast")
    void duplicateTypeFailsAtStartup() {
        ChatPipeline a = mock(ChatPipeline.class);
        ChatPipeline b = mock(ChatPipeline.class);
        when(a.type()).thenReturn(PipelineType.CLASSIC_RAG);
        when(b.type()).thenReturn(PipelineType.CLASSIC_RAG);

        assertThatThrownBy(() -> new ChatPipelineRegistry(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate_type");
    }

    @Test
    @DisplayName("get(未注册 type) → DomainException PIPELINE_NOT_FOUND, 失败关闭")
    void missingTypeFailsClosed() {
        ChatPipeline classic = mock(ChatPipeline.class);
        when(classic.type()).thenReturn(PipelineType.CLASSIC_RAG);
        ChatPipelineRegistry registry = new ChatPipelineRegistry(List.of(classic));

        assertThatThrownBy(() -> registry.get(PipelineType.AGENTIC_RAG))
                .isInstanceOf(DomainException.class)
                .satisfies(
                        ex ->
                                assertThat(((DomainException) ex).errorCode().name())
                                        .isEqualTo("PIPELINE_NOT_FOUND"));
    }

    @Test
    @DisplayName("正常注册可按 type 取出 + registeredTypes 返回不可变 snapshot")
    void normalRegistrationWorks() {
        ChatPipeline classic = mock(ChatPipeline.class);
        when(classic.type()).thenReturn(PipelineType.CLASSIC_RAG);
        ChatPipelineRegistry registry = new ChatPipelineRegistry(List.of(classic));

        assertThat(registry.get(PipelineType.CLASSIC_RAG)).isSameAs(classic);
        assertThat(registry.registeredTypes()).containsExactly(PipelineType.CLASSIC_RAG);
        assertThatThrownBy(() -> registry.registeredTypes().add(PipelineType.TARGETED_RAG))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
