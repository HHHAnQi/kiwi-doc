package com.xxx.ragdoc.application.chat.router;

import com.xxx.ragdoc.application.chat.router.RuleBasedTaskRouter;
import com.xxx.ragdoc.application.chat.router.TaskRouter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** PR-3.2: 把 RuleBasedTaskRouter (platform-common 纯函数实现) 暴露为 Spring bean, 供 ChatOrchestrator 注入。 */
@Configuration
public class TaskRouterAutoConfiguration {

    @Bean
    public TaskRouter ruleBasedTaskRouter() {
        return new RuleBasedTaskRouter();
    }
}
