package com.xxx.ragdoc.application.chat.harness;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * PR-5 / EMS-PR5: Harness 配置。默认关闭; 生产不可被普通用户请求切换 (无 controller endpoint 暴露)。
 *
 * <pre>
 *   rag:
 *     agent:
 *       harness:
 *         enabled: false
 *         mode: LIVE  / RECORD / REPLAY
 *         fixture-root: target/agent-fixtures  (或测试临时目录)
 *         strict-replay: true
 *         record-sensitive-content: false  (PR-5 一定 false; 未来是否允许保存原文是治理决策)
 * </pre>
 *
 * <p>客户端请求体不能切换 mode; 只能由服务端配置或测试的 ApplicationContextInitialier 设置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.agent.harness")
public class HarnessProperties {
    /** {@code rag.agent.harness.enabled}, 默认 false (LIVE 路径不进 Harness)。 */
    private boolean enabled = false;
    /** 须与 enabled=true 联动; 默认 LIVE (即开启也只观察)。 */
    private HarnessMode mode = HarnessMode.LIVE;
    /** Fixture root 目录; 生产默认系统 temp 子目录避免污染工作树; 测试可用 @TempDirectory 覆盖。 */
    private String fixtureRoot = System.getProperty("java.io.tmpdir") + "/ragdoc-agent-fixtures";
    /** 严格模式默认 true (任何不一致直接失败, 不回退)。 */
    private boolean strictReplay = true;
    /** PR-5 永远 false (禁止保存原始 Principal/Token 等敏感字段)。 */
    private boolean recordSensitiveContent = false;
    /** 写入 fixture 的来源标识 metadata (test/ci/local/prod-replay 等)。 */
    private String sourceModeTag = "default";
}
