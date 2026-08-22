package com.xxx.ragdoc.interfaces.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.chat.ChatService;
import com.xxx.ragdoc.application.chat.RetrieveService;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * P1: 把平台检索/问答能力封装为 MCP (Model Context Protocol) Server, stdio 传输。
 *
 * <p>让 Claude Desktop / Cursor / 任意 MCP Host 把本知识库当工具用 — JD 高频关键词
 * "MCP Server 封装与接入" 的落地。
 *
 * <h3>启动</h3>
 *
 * <pre>
 * java -jar platform-bootstrap.jar --rag.mcp.server.enabled=true
 * # MCP host 配置: {"mcpServers":{"ragdoc":{"command":"java","args":["-jar","...","--rag.mcp.server.enabled=true"]}}}
 * </pre>
 *
 * <h3>安全边界</h3>
 *
 * <ul>
 *   <li>stdio 传输 = 本地进程间通信, 信任边界与宿主一致(MCP 标准模型)
 *   <li>内部以<b>最小权限服务主体</b>调用业务链路: role:user 非 admin → 检索范围
 *       自动排除 PRIVATE 文档(AclPermissionResolver), tenant 可配
 *       ({@code rag.mcp.server.tenant-id}, 默认 default)
 *   <li>stdout 是协议通道: 启动时把日志 console appender 切到 stderr, 防日志污染帧
 * </ul>
 *
 * <h3>协议</h3>
 *
 * <p>newline-delimited JSON-RPC 2.0。支持 initialize / notifications/initialized / ping /
 * tools/list / tools/call; 工具: {@code rag_search}(纯检索) 与 {@code rag_ask}(检索+生成)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.mcp.server", name = "enabled", havingValue = "true")
public class McpStdioServer implements CommandLineRunner {

    private final RetrieveService retrieveService;
    private final ChatService chatService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "ragdoc";
    private static final String SERVER_VERSION = "0.1.0";

    @org.springframework.beans.factory.annotation.Value(
            "${rag.mcp.server.tenant-id:default}")
    private String tenantId;

    @Override
    public void run(String... args) throws Exception {
        redirectConsoleLoggingToStderr();
        log.info("mcp.server_starting tools=[rag_search, rag_ask] tenant={}", tenantId);
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            String response = handle(line);
            if (response != null) {
                System.out.println(response);
                System.out.flush();
            }
        }
        log.info("mcp.server_stopped (stdin closed)");
    }

    /** 单条 JSON-RPC 处理; notification(无 id) 返 null 不应答。 */
    String handle(String raw) {
        JsonNode req;
        try {
            req = objectMapper.readTree(raw);
        } catch (Exception e) {
            return error(null, -32700, "Parse error");
        }
        JsonNode id = req.get("id"); // null = notification
        String method = req.path("method").asText("");
        try {
            return switch (method) {
                case "initialize" -> result(id, initializeResult());
                case "notifications/initialized", "notifications/cancelled" -> null;
                case "ping" -> result(id, objectMapper.createObjectNode());
                case "tools/list" -> result(id, toolsListResult());
                case "tools/call" -> result(id, toolsCall(req.path("params")));
                default -> id == null ? null : error(id, -32601, "Method not found: " + method);
            };
        } catch (Exception e) {
            log.warn("mcp.handle_failed method={}, reason={}", method, e.getMessage());
            return id == null ? null : error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    // ─── MCP 结果构造 ──────────────────────────────────────────────

    private ObjectNode initializeResult() {
        ObjectNode r = objectMapper.createObjectNode();
        r.put("protocolVersion", PROTOCOL_VERSION);
        r.putObject("capabilities").putObject("tools");
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", SERVER_NAME);
        info.put("version", SERVER_VERSION);
        return r;
    }

    private ObjectNode toolsListResult() {
        ObjectNode r = objectMapper.createObjectNode();
        ArrayNode tools = r.putArray("tools");

        ObjectNode search = tools.addObject();
        search.put("name", "rag_search");
        search.put("description", "在企业知识库(Spring Cloud Alibaba 等技术文档)中做混合检索, 返回相关片段");
        ObjectNode sSchema = search.putObject("inputSchema");
        sSchema.put("type", "object");
        sSchema.putObject("properties")
                .putObject("query")
                .put("type", "string")
                .put("description", "检索问题(1-500 字)");
        sSchema.putArray("required").add("query");

        ObjectNode ask = tools.addObject();
        ask.put("name", "rag_ask");
        ask.put("description", "基于企业知识库检索并生成带引用编号的答案; 无相关内容时会明确拒答");
        ObjectNode aSchema = ask.putObject("inputSchema");
        aSchema.put("type", "object");
        aSchema.putObject("properties")
                .putObject("query")
                .put("type", "string")
                .put("description", "用户问题(1-500 字)");
        aSchema.putArray("required").add("query");
        return r;
    }

    /** tools/call: 以最小权限服务主体执行, AuthContext 用完即清防串号。 */
    private ObjectNode toolsCall(JsonNode params) {
        String tool = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        AuthContext.set(servicePrincipal());
        try {
            return switch (tool) {
                case "rag_search" -> textContent(invokeSearch(args));
                case "rag_ask" -> textContent(invokeAsk(args));
                default -> errorTool("Unknown tool: " + tool);
            };
        } catch (IllegalArgumentException e) {
            return errorTool(e.getMessage());
        } catch (Exception e) {
            log.warn("mcp.tool_failed tool={}, reason={}", tool, e.getMessage());
            return errorTool("tool execution failed: " + e.getMessage());
        } finally {
            AuthContext.clear();
        }
    }

    private String invokeSearch(JsonNode args) {
        ChatCommand cmd = toCommand(args);
        RetrieveService.RetrieveResult r = retrieveService.retrieve(cmd);
        ArrayNode arr = objectMapper.createArrayNode();
        for (RetrieveService.Citation c : r.items()) {
            ObjectNode o = arr.addObject();
            o.put("chunk_id", c.chunkId());
            o.put("doc_id", c.docId());
            o.put("page", c.page());
            o.put("score", c.score());
            o.put("snippet", c.snippet());
            ArrayNode sp = o.putArray("section_path");
            if (c.sectionPath() != null) c.sectionPath().forEach(sp::add);
        }
        return objectMapper.valueToTree(
                        java.util.Map.of("results", arr, "count", r.items().size()))
                .toString();
    }

    private String invokeAsk(JsonNode args) {
        ChatCommand cmd = toCommand(args);
        ChatResult r = chatService.chat(cmd, new TraceId("mcp-" + UUID.randomUUID()));
        ObjectNode o = objectMapper.createObjectNode();
        o.put("answer", r.answer());
        o.put("state_hint", r.stateHint() == null ? "OK" : r.stateHint().name());
        o.put("trace_id", r.traceId() == null ? "" : r.traceId().value());
        ArrayNode cits = o.putArray("citations");
        if (r.citations() != null) {
            r.citations().forEach(
                    c -> {
                        ObjectNode cn = cits.addObject();
                        cn.put("n", cits.size() + 1);
                        cn.put("chunk_id", c.chunkId());
                        cn.put("doc_id", c.docId());
                        cn.put("snippet", c.snippet());
                    });
        }
        return o.toString();
    }

    private ChatCommand toCommand(JsonNode args) {
        String query = args.path("query").asText("");
        return new ChatCommand(
                query,
                args.hasNonNull("doc_id") ? args.path("doc_id").asLong() : null,
                args.hasNonNull("top_k") ? args.path("top_k").asInt() : 5,
                args.hasNonNull("source") ? args.path("source").asText() : null,
                args.hasNonNull("version") ? args.path("version").asText() : null,
                args.hasNonNull("language") ? args.path("language").asText() : null,
                null); // MCP 工具调用为单轮, 不挂会话
    }

    private Principal servicePrincipal() {
        return new Principal(
                tenantId == null || tenantId.isBlank() ? "default" : tenantId,
                "mcp-server",
                Set.of("role:default", "role:user"), // 最小权限: 非 admin, PRIVATE 文档不可见
                "mcp-internal");
    }

    // ─── JSON-RPC 封装 ────────────────────────────────────────────

    private String result(JsonNode id, JsonNode result) {
        ObjectNode r = objectMapper.createObjectNode();
        if (id != null) r.set("id", id);
        r.put("jsonrpc", "2.0");
        r.set("result", result);
        return r.toString();
    }

    private String error(JsonNode id, int code, String message) {
        ObjectNode r = objectMapper.createObjectNode();
        if (id != null) r.set("id", id);
        r.put("jsonrpc", "2.0");
        ObjectNode e = r.putObject("error");
        e.put("code", code);
        e.put("message", message);
        return r.toString();
    }

    /** tools/call 的 content 格式成功结果。 */
    private ObjectNode textContent(String text) {
        ObjectNode r = objectMapper.createObjectNode();
        r.putArray("content").addObject().put("type", "text").put("text", text);
        return r;
    }

    /** tools/call 的 isError 结果(MCP 规范: 工具级错误走 content + isError, 不走 JSON-RPC error)。 */
    private ObjectNode errorTool(String message) {
        ObjectNode r = objectMapper.createObjectNode();
        r.putArray("content").addObject().put("type", "text").put("text", message);
        r.put("isError", true);
        return r;
    }

    /** stdout 是协议通道: 把 logback ConsoleAppender 的 target 切到 stderr。 */
    private static void redirectConsoleLoggingToStderr() {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        var it = root.iteratorForAppenders();
        while (it.hasNext()) {
            var a = it.next();
            if (a instanceof ch.qos.logback.core.ConsoleAppender<?> console) {
                console.stop();
                console.setTarget("System.err");
                console.start();
            }
        }
    }
}
