package com.xxx.ragdoc.application.chat.harness;

/**
 * PR-5 / EMS-PR5: Harness 模式。默认 LIVE; 生产不可被普通用户请求切换。
 *
 * <ul>
 *   <li>{@link #LIVE} — 直接调真实组件, 不读/写 Fixture; Harness 仅做 metrics
 *   <li>{@link #RECORD} — 调真实组件, 同时把请求/响应脱敏后写到 FixtureStore (原子写入)
 *   <li>{@link #REPLAY} — <b>不</b> 调真实组件, 直接从 FixtureStore 读对应 ReplayKey; 缺失/不匹配/损坏 → 失败关闭,
 *       严格不允许 fallback LIVE
 * </ul>
 *
 * <p>Mode 来源只能是服务端配置 / 测试配置 / 受控内部命令; 客户端请求体不允许切换。
 */
public enum HarnessMode {
    LIVE,
    RECORD,
    REPLAY
}
