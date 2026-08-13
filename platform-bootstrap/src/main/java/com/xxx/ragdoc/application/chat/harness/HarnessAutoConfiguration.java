package com.xxx.ragdoc.application.chat.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PR-5 / EMS-PR5: 根据 {@link HarnessProperties} 装配对应的 {@link HarnessProvider} bean。
 *
 * <p>enabled=false (默认) → 直接返回 {@link LiveHarnessProvider}; 让 HarnessAwareTaskRouter /
 * ToolHarnessAdapter 注入接口即 LIVE 行为, 无运行时开销 (ComponentInvocation 也不构造)。
 *
 * <p>enabled=true & mode=RECORD → RecordHarnessProvider
 *
 * <p>enabled=true & mode=REPLAY → ReplayHarnessProvider
 *
 * <p>enabled=true & mode=LIVE → LiveHarnessProvider (与 disabled 完全行为一致; 仅 metrics 区分)
 */
@Slf4j
@Configuration
public class HarnessAutoConfiguration {

    @Bean
    public HarnessProvider harnessProvider(
            HarnessProperties props,
            @Autowired(required = false) FixtureStore store,
            ObjectMapper mapper) {
        if (!props.isEnabled()) {
            return new LiveHarnessProvider();
        }
        return switch (props.getMode()) {
            case LIVE -> {
                log.info("harness.enabled LIVE (observe-only, no fixture IO)");
                yield new LiveHarnessProvider();
            }
            case RECORD -> {
                log.info(
                        "harness.enabled RECORD root={} tag={}",
                        props.getFixtureRoot(),
                        props.getSourceModeTag());
                yield new RecordHarnessProvider(
                        store != null
                                ? store
                                : new FileFixtureStore(props.getFixtureRoot(), mapper),
                        mapper,
                        props.getSourceModeTag());
            }
            case REPLAY -> {
                log.info(
                        "harness.enabled REPLAY root={} strict={}",
                        props.getFixtureRoot(),
                        props.isStrictReplay());
                yield new ReplayHarnessProvider(
                        store != null
                                ? store
                                : new FileFixtureStore(props.getFixtureRoot(), mapper),
                        mapper,
                        props.isStrictReplay());
            }
        };
    }

    /** enabled=true 时配置 FileFixtureStore bean; 无 enabled 时 store=null (LiveHarnessProvider 用不到)。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "rag.agent.harness",
            name = "enabled",
            havingValue = "true")
    public FixtureStore fixtureStore(HarnessProperties props, ObjectMapper mapper) {
        return new FileFixtureStore(props.getFixtureRoot(), mapper);
    }
}
