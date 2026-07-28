package com.xxx.ragdoc.domain.shared;

/** 文件内容 hash(SHA256 hex)。作为上传幂等键。 校验见 docs/data/data-model.md §4。 */
public record ContentHash(String value) {

    private static final java.util.regex.Pattern SHA256_HEX =
            java.util.regex.Pattern.compile("^[a-fA-F0-9]{64}$");

    public ContentHash {
        if (value == null || !SHA256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException("ContentHash 必须是 64 位 SHA256 hex 字符串, 实际: " + value);
        }
    }
}
