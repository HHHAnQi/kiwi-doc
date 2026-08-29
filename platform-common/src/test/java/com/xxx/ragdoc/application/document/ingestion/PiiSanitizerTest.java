package com.xxx.ragdoc.application.document.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PiiSanitizerTest {
    @Test
    void masksPiiAndSecretsBeforeIndexing() {
        var result =
                new PiiSanitizer()
                        .sanitize(
                                "联系 13812345678 或 a@example.com，身份证 11010519491231002X，api_key=abcdef123456");
        assertThat(result.text())
                .doesNotContain(
                        "13812345678", "a@example.com", "11010519491231002X", "abcdef123456");
        assertThat(result.totalRedactions()).isEqualTo(4);
    }
}
