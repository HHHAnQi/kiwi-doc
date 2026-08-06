package com.xxx.ragdoc.application.chat.router;

import com.xxx.ragdoc.application.chat.harness.HarnessAwareTaskRouter;
import com.xxx.ragdoc.application.chat.harness.HarnessProperties;
import com.xxx.ragdoc.application.chat.harness.HarnessProvider;
import com.xxx.ragdoc.application.chat.harness.RouterHarnessAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PR-3.2 + PR-5: 把 RuleBasedTaskRouter (platform-common 纯函数实现) 暴露为 Spring bean。
 *
 * <p>PR-5: 如果 Harness 启用, 返回 {@link HarnessAwareTaskRouter}; 否则直接返回原 RuleBasedTaskRouter
 * (零开销, PR-3 行为完全不变)。
 */
@Configuration
public class TaskRouterAutoConfiguration {

    @Bean
    public TaskRouter ruleBasedTaskRouter(
            HarnessProperties harnessProps,
            HarnessProvider harnessProvider,
            ObjectMapper mapper) {
        RuleBasedTaskRouter real = new RuleBasedTaskRouter();
        if (!harnessProps.isEnabled()) {
            return real;
        }
        // PR-5: harness enabled → 用包装 (auto-degrade to LIVE in wrapper if mode=LIVE)
        return new HarnessAwareTaskRouter(real, harnessProvider, harnessProps, new RouterHarnessAdapter(mapper));
    }
}

