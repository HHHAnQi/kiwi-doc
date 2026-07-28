package com.xxx.ragdoc.domain.feedback;

/** 反馈评分枚举。与 feedbacks 表 rating 列的字符串值对齐。 */
public enum Rating {
    LIKE("like"),
    DISLIKE("dislike");

    private final String dbValue;

    Rating(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 从数据库或入参字符串解析为枚举; 非法值抛 IllegalArgumentException。 */
    public static Rating parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("rating 不能为空");
        }
        for (Rating r : values()) {
            if (r.dbValue.equalsIgnoreCase(value.trim())) {
                return r;
            }
        }
        throw new IllegalArgumentException("rating 非法: " + value + " (仅支持 like / dislike)");
    }
}
