package com.xxx.ragdoc.application.chat.tool;

import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-4 / EMS-PR4: 唯一的 Agent Tool Registry。把所有 {@link AgentTool} Spring bean 收集进 name+version 索引。
 *
 * <h2>启动期校验 (fail-fast)</h2>
 *
 * <ul>
 *   <li>(name, version) 重复 → IllegalStateException (日后两个 bean 标 semantic_search:v1 会立即暴露)
 *   <li>{@link ToolDescriptor} 字段非法 (name 命名规则 / timeout≤0 / maxResults 越界 等) → ToolDescriptor 自己构造校验抛
 *   <li>每个 bean 的 inputType/outputType 与 interface generic 参数一致 → 由 JVM generic erasure 难以静态校验,
 *       Registry 把 inputType 必须实现 {@link ToolInput}, outputType 必须实现 {@link ToolOutput} 的契约抛出
 * </ul>
 *
 * <h2>运行时校验 (fail-closed)</h2>
 *
 * <ul>
 *   <li>{@link #get(String, String)} 未命中 → {@link ErrorCode#TOOL_NOT_FOUND} (HTTP 404), 不允许回退到任意其它 tool
 *   <li>{@link #list()} 返回按 name 排序的 descriptor snapshot, 让 Planner / 调试 endpoint 可枚举
 * </ul>
 *
 * <p>Registry 只负责查找, <b>不</b> 执行:
 *
 * <ul>
 *   <li>不解析 description文字执行逻辑 (prompt-injection 防护)
 *   <li>不做 ACL (ToolExecutor / Tool 内部完成)
 *   <li>不做 dedup (ToolExecutor 完成)
 *   <li>不做反射执行任意 Bean (Spring Bean 自身的 execute 方法已被强类型化为泛型)
 * </ul>
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, AgentTool<? extends ToolInput, ? extends ToolOutput>> byKey = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool<? extends ToolInput, ? extends ToolOutput>> tools) {
        for (AgentTool<? extends ToolInput, ? extends ToolOutput> t : tools) {
            ToolDescriptor d = t.descriptor();
            // descriptor 字段合法性已由 ToolDescriptor 构造校验; 这里再二次确认 inputType/outputType 标记接口
            if (!ToolInput.class.isAssignableFrom(t.inputType())) {
                throw new IllegalStateException(
                        "ToolRegistry.invalid_input_type name=" + d.name() + " inputType=" + t.inputType()
                                + " 必须实现 ToolInput");
            }
            if (!ToolOutput.class.isAssignableFrom(t.outputType())) {
                throw new IllegalStateException(
                        "ToolRegistry.invalid_output_type name=" + d.name() + " outputType=" + t.outputType()
                                + " 必须实现 ToolOutput");
            }
            String key = key(d.name(), d.version());
            AgentTool<? extends ToolInput, ? extends ToolOutput> prev = byKey.put(key, t);
            if (prev != null) {
                throw new IllegalStateException(
                        "ToolRegistry.duplicate name=" + d.name() + " version=" + d.version()
                                + " bean1=" + prev.getClass().getName()
                                + " bean2=" + t.getClass().getName());
            }
            log.info(
                    "tool.registry registered name={} version={} perm={} cost={} impl={}",
                    d.name(),
                    d.version(),
                    d.requiredPermission(),
                    d.costCategory(),
                    t.getClass().getSimpleName());
        }
        if (byKey.isEmpty()) {
            log.warn("tool.registry empty — no AgentTool bean found");
        }
    }

    /** 按 (name, version) 精确查找; 缺失 → DomainException TOOL_NOT_FOUND。 */
    @SuppressWarnings("unchecked")
    public <I extends ToolInput, O extends ToolOutput> AgentTool<I, O> get(String name, String version) {
        AgentTool<? extends ToolInput, ? extends ToolOutput> t = byKey.get(key(name, version));
        if (t == null) {
            throw new DomainException(
                    ErrorCode.TOOL_NOT_FOUND,
                    "请求的 tool 未注册: " + name + ":" + version + " (已注册: " + byKey.keySet() + ")");
        }
        return (AgentTool<I, O>) t;
    }

    /** 仅按 name 查找最新版本 (默认 v1); 主要供 Planner 以后用, 当前 PR-4 单版本。 */
    public AgentTool<? extends ToolInput, ? extends ToolOutput> getByName(String name) {
        return byKey.values().stream()
                .filter(t -> t.descriptor().name().equals(name))
                .max(Comparator.comparing(t -> t.descriptor().version()))
                .orElseThrow(
                        () ->
                                new DomainException(
                                        ErrorCode.TOOL_NOT_FOUND,
                                        "tool name 未注册: " + name + " (已知: " + byKey.keySet() + ")"));
    }

    /** 调试 / Planner 枚举所有 descriptor, 按 name 排序。返回不可变 snapshot。 */
    public List<ToolDescriptor> list() {
        List<ToolDescriptor> out = new ArrayList<>();
        byKey.values().stream()
                .sorted(Comparator.comparing(t -> t.descriptor().name()))
                .forEach(t -> out.add(t.descriptor()));
        return List.copyOf(out);
    }

    /** 已注册的 (name:version) 集合 snapshot, 给测试断言。 */
    public java.util.Set<String> registeredKeys() {
        return java.util.Collections.unmodifiableSet(byKey.keySet());
    }

    static String key(String name, String version) {
        return name + ":" + version;
    }
}
