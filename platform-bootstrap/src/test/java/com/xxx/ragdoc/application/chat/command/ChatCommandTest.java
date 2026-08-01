package com.xxx.ragdoc.application.chat.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-SMOKE-4: {@link ChatCommand} 元数据字段规范化 + 向后兼容老构造方法。
 *
 * <p>防 ChatCommand 未来加字段时漏处理 source/version/language 空白规范化。
 */
@DisplayName("ChatCommand")
class ChatCommandTest {

    @Test
    @DisplayName("老 3 参构造(query,docId,topK): 向后兼容, source/version/language 全为 null")
    void legacyConstructorShouldBeNullMetadata() {
        ChatCommand cmd = new ChatCommand("Sentinel 怎么用", null, 5);
        assertThat(cmd.query()).isEqualTo("Sentinel 怎么用");
        assertThat(cmd.topK()).isEqualTo(5);
        assertThat(cmd.source()).isNull();
        assertThat(cmd.version()).isNull();
        assertThat(cmd.language()).isNull();
    }

    @Test
    @DisplayName("新构造 + 空白 metadata: 规范化为 null")
    void blankMetadataNormalizedToNull() {
        ChatCommand cmd = new ChatCommand("Sentinel", null, 5, "  ", "  ", "\t");
        assertThat(cmd.source()).isNull();
        assertThat(cmd.version()).isNull();
        assertThat(cmd.language()).isNull();
    }

    @Test
    @DisplayName("新构造 + 真值: 去 trim 首尾空白")
    void realMetadataTrimmed() {
        ChatCommand cmd = new ChatCommand("q", null, 5, "  nacos  ", "  2.4 ", " zh ");
        assertThat(cmd.source()).isEqualTo("nacos");
        assertThat(cmd.version()).isEqualTo("2.4");
        assertThat(cmd.language()).isEqualTo("zh");
    }
}
