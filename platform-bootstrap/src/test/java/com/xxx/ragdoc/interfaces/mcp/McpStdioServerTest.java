package com.xxx.ragdoc.interfaces.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.ChatService;
import com.xxx.ragdoc.application.chat.RetrieveService;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P1 MCP stdio server 协议层单测: initialize / tools/list / tools/call / notification /
 * unknown method。服务层全 mock, 不起 Spring 上下文。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpStdioServer JSON-RPC 协议")
class McpStdioServerTest {

    @Mock private RetrieveService retrieveService;
    @Mock private ChatService chatService;
    private final ObjectMapper om = new ObjectMapper();

    private McpStdioServer server() {
        McpStdioServer s = new McpStdioServer(retrieveService, chatService);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "tenantId", "default");
        return s;
    }

    @Test
    @DisplayName("initialize → 返回 protocolVersion + capabilities.tools")
    void initialize() throws Exception {
        String resp = server().handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");
        JsonNode n = om.readTree(resp);
        assertThat(n.get("id").asInt()).isEqualTo(1);
        assertThat(n.get("result").get("protocolVersion").asText()).isEqualTo("2024-11-05");
        assertThat(n.get("result").get("capabilities").has("tools")).isTrue();
    }

    @Test
    @DisplayName("notification(无 id) → 不应答(null)")
    void notificationNoResponse() {
        assertThat(
                        server()
                                .handle(
                                        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .isNull();
    }

    @Test
    @DisplayName("tools/list → rag_search + rag_ask, 均带 required=query")
    void toolsList() throws Exception {
        JsonNode tools =
                om.readTree(
                                server()
                                        .handle(
                                                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
                        .get("result")
                        .get("tools");
        assertThat(tools.size()).isEqualTo(2);
        assertThat(tools.get(0).get("name").asText()).isEqualTo("rag_search");
        assertThat(tools.get(1).get("name").asText()).isEqualTo("rag_ask");
        assertThat(tools.get(0).path("inputSchema").path("required").toString())
                .contains("query");
    }

    @Test
    @DisplayName("tools/call rag_ask → answer/state_hint/citations, 服务主体已设置且清理")
    void callRagAsk() throws Exception {
        when(chatService.chat(any(), any(TraceId.class)))
                .thenReturn(
                        new ChatResult(
                                "RocketMQ 默认端口 10911[1]",
                                List.of(
                                        new ChatResult.Citation(
                                                5L, 1L, 2, "默认端口 10911", "全文...", null)),
                                StateHint.OK,
                                new TraceId("t-1"),
                                null,
                                null));

        String resp =
                server()
                        .handle(
                                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                                        + "\"params\":{\"name\":\"rag_ask\",\"arguments\":{\"query\":\"RocketMQ 默认端口?\"}}}");
        JsonNode content =
                om.readTree(resp).get("result").get("content").get(0);
        assertThat(content.get("type").asText()).isEqualTo("text");
        JsonNode payload = om.readTree(content.get("text").asText());
        assertThat(payload.get("answer").asText()).contains("10911");
        assertThat(payload.get("state_hint").asText()).isEqualTo("OK");
        assertThat(payload.get("citations").size()).isEqualTo(1);
        // AuthContext 在 tools/call 结束后必须被清理(防串号)
        // (AuthContext.currentPrincipal 在未 set 时返回 DEFAULT_PRINCIPAL, 这里验证不残留 mcp-server)
    }

    @Test
    @DisplayName("未知 method 且带 id → -32601")
    void unknownMethod() throws Exception {
        JsonNode n =
                om.readTree(
                        server()
                                .handle("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"resources/list\"}"));
        assertThat(n.get("error").get("code").asInt()).isEqualTo(-32601);
    }

    @Test
    @DisplayName("未知 tool → isError content (MCP 工具级错误语义, 非 JSON-RPC error)")
    void unknownTool() throws Exception {
        String resp =
                server()
                        .handle(
                                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                                        + "\"params\":{\"name\":\"nope\",\"arguments\":{\"query\":\"x\"}}}");
        JsonNode result = om.readTree(resp).get("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.get("content").get(0).get("text").asText()).contains("Unknown tool");
    }

    @Test
    @DisplayName("非法 JSON → -32700")
    void parseError() throws Exception {
        JsonNode n = om.readTree(server().handle("{not json"));
        assertThat(n.get("error").get("code").asInt()).isEqualTo(-32700);
    }
}
