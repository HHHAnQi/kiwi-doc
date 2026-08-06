package com.xxx.ragdoc.application.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.common.exception.DomainException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PR-4: {@link ToolRegistry} 启动 fail-fast + 运行时 fail-closed + descriptor 校验。 */
@DisplayName("ToolRegistry - PR-4 fail-fast / fail-closed")
class ToolRegistryTest {

    @SuppressWarnings("unchecked")
    private static AgentTool<ToolInput, ToolOutput> stub(String name, String version) {
        AgentTool<ToolInput, ToolOutput> t = mock(AgentTool.class);
        ToolDescriptor d =
                new ToolDescriptor(
                        name,
                        version,
                        "stub for test",
                        "v1",
                        "v1",
                        ToolPermission.READ_RETRIEVE,
                        Duration.ofSeconds(5),
                        10,
                        true,
                        ToolCostCategory.INDEX_READ);
        when(t.descriptor()).thenReturn(d);
        when(t.inputType()).thenReturn((Class<ToolInput>) (Class<?>) StubInput.class);
        when(t.outputType()).thenReturn((Class<ToolOutput>) (Class<?>) StubOutput.class);
        return t;
    }

    record StubInput(String q) implements ToolInput {}
    record StubOutput() implements ToolOutput {}

    @Test
    @DisplayName("重复 (name, version) → 启动 IllegalStateException")
    void duplicateNameVersion() {
        AgentTool<ToolInput, ToolOutput> a = stub("semantic_search", "v1");
        AgentTool<ToolInput, ToolOutput> b = stub("semantic_search", "v1");
        assertThatThrownBy(() -> new ToolRegistry(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    @DisplayName("同 name 不同 version 可共存 (后续多版本 Tool)")
    void sameNameDifferentVersion() {
        AgentTool<ToolInput, ToolOutput> a = stub("semantic_search", "v1");
        AgentTool<ToolInput, ToolOutput> b = stub("semantic_search", "v2");
        ToolRegistry r = new ToolRegistry(List.of(a, b));
        assertThat(r.registeredKeys()).contains("semantic_search:v1", "semantic_search:v2");
    }

    @Test
    @DisplayName("get(name, version) 未注册 → DomainException TOOL_NOT_FOUND")
    void missingFailsClosed() {
        ToolRegistry r = new ToolRegistry(List.of(stub("semantic_search", "v1")));
        assertThatThrownBy(() -> r.get("nonexistent", "v1"))
                .isInstanceOf(DomainException.class)
                .satisfies(
                        ex ->
                                assertThat(((DomainException) ex).errorCode().name())
                                        .isEqualTo("TOOL_NOT_FOUND"));
    }

    @Test
    @DisplayName("descriptor name 必须小写下划线; 非法格式 → 构造抛")
    void invalidToolName() {
        assertThatThrownBy(
                        () ->
                                new ToolDescriptor(
                                        "SemanticSearch", // 大写非法
                                        "v1",
                                        "x",
                                        "v1",
                                        "v1",
                                        ToolPermission.READ_RETRIEVE,
                                        Duration.ofSeconds(5),
                                        10,
                                        true,
                                        ToolCostCategory.INDEX_READ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("descriptor timeout ≤ 0 / maxResults 越界 → 构造抛")
    void invalidTimeoutOrMax() {
        assertThatThrownBy(
                        () ->
                                new ToolDescriptor(
                                        "x_y",
                                        "v1",
                                        "x",
                                        "v1",
                                        "v1",
                                        ToolPermission.READ_RETRIEVE,
                                        Duration.ZERO, // illegal
                                        10,
                                        true,
                                        ToolCostCategory.INDEX_READ))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new ToolDescriptor(
                                        "x_y",
                                        "v1",
                                        "x",
                                        "v1",
                                        "v1",
                                        ToolPermission.READ_RETRIEVE,
                                        Duration.ofSeconds(1),
                                        0, // illegal
                                        true,
                                        ToolCostCategory.INDEX_READ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("list 返回排序后 descriptor snapshot, 不可变")
    void listImmutableSnapshot() {
        ToolRegistry r =
                new ToolRegistry(
                        List.of(
                                stub("z_tool", "v1"),
                                stub("a_tool", "v1")));
        List<ToolDescriptor> ds = r.list();
        assertThat(ds).extracting(ToolDescriptor::name).containsExactly("a_tool", "z_tool");
        assertThatThrownBy(() -> ((List<ToolDescriptor>) ds).add(ds.get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("inputType/outputType 不实现 ToolInput/ToolOutput → 启动抛")
    void invalidInputOutputType() {
        AgentTool rawTool = mock(AgentTool.class);
        when(rawTool.descriptor())
                .thenReturn(
                        new ToolDescriptor(
                                "bad_tool",
                                "v1",
                                "bad",
                                "v1",
                                "v1",
                                ToolPermission.READ_RETRIEVE,
                                Duration.ofSeconds(5),
                                10,
                                true,
                                ToolCostCategory.INDEX_READ));
        when(rawTool.inputType()).thenReturn((Class) Object.class); // 不实现 ToolInput
        when(rawTool.outputType()).thenReturn((Class) Object.class);

        assertThatThrownBy(() -> new ToolRegistry(List.of(rawTool)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid_input_type");
    }
}
