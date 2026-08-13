package com.xxx.ragdoc.application.document.ingestion;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 入库前确定性脱敏。原文仍只保存在受控 MinIO，Chunk/MySQL/Milvus 只接收脱敏文本。 */
@Component
public class PiiSanitizer {

    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();

    static {
        RULES.put("SECRET", Pattern.compile("(?i)(api[_-]?key|secret|password|access[_-]?token)\\s*[:=]\\s*[^\\s,;]{6,}"));
        RULES.put("ID_CARD", Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)"));
        RULES.put("BANK_CARD", Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)"));
        RULES.put("PHONE", Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"));
        RULES.put("EMAIL", Pattern.compile("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}"));
    }

    public Result sanitize(String text) {
        String sanitized = text == null ? "" : text;
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (var entry : RULES.entrySet()) {
            var matcher = entry.getValue().matcher(sanitized);
            int count = 0;
            while (matcher.find()) count++;
            if (count > 0) {
                hits.put(entry.getKey(), count);
                sanitized = matcher.replaceAll("[REDACTED_" + entry.getKey() + "]");
            }
        }
        return new Result(sanitized, Map.copyOf(hits));
    }

    public record Result(String text, Map<String, Integer> redactionCounts) {
        public int totalRedactions() {
            return redactionCounts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
