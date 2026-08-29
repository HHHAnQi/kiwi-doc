package com.xxx.ragdoc.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.chat.CitationVerifierProperties;
import com.xxx.ragdoc.application.chat.ConversationProperties;
import com.xxx.ragdoc.application.chat.QueryEnhanceProperties;
import com.xxx.ragdoc.application.chat.RerankProperties;
import com.xxx.ragdoc.application.chat.pipeline.ChatPipelineRegistry;
import com.xxx.ragdoc.application.chat.planner.PlannerProperties;
import com.xxx.ragdoc.application.chat.router.RouterProperties;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.infrastructure.milvus.RetrieveProperties;
import com.xxx.ragdoc.infrastructure.trace.LangfuseProperties;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class RagCapabilityRegistryTest {

    @Test
    void defaultConfigurationProducesExplicitDisabledSnapshot() throws Exception {
        RagCapabilityRegistry registry =
                registry(new PlannerProperties(), new ConversationProperties());

        registry.run(null);

        assertThat(registry.snapshot())
                .containsEntry("router", RagCapabilityRegistry.Status.DISABLED)
                .containsEntry("hybridRetrieval", RagCapabilityRegistry.Status.DISABLED)
                .containsEntry("agenticRag", RagCapabilityRegistry.Status.DISABLED);
    }

    @Test
    void plannedPipelineWithoutPlannerFailsFast() {
        PlannerProperties planner = new PlannerProperties();
        planner.setPlannedPipelineEnabled(true);

        assertThatThrownBy(() -> registry(planner, new ConversationProperties()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("planner.enabled");
    }

    @Test
    void conversationCompressionWithoutConversationFailsFast() {
        ConversationProperties conversation = new ConversationProperties();
        conversation.setCompress(true);

        assertThatThrownBy(() -> registry(new PlannerProperties(), conversation).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conversation.enabled");
    }

    private static RagCapabilityRegistry registry(
            PlannerProperties planner, ConversationProperties conversation) {
        ChatPipelineRegistry pipelines = mock(ChatPipelineRegistry.class);
        when(pipelines.registeredTypes()).thenReturn(EnumSet.allOf(PipelineType.class));
        return new RagCapabilityRegistry(
                new RouterProperties(),
                conversation,
                new QueryEnhanceProperties(),
                new RetrieveProperties(),
                new RerankProperties(),
                new CitationVerifierProperties(),
                planner,
                new LangfuseProperties(),
                pipelines);
    }
}
