package com.xxx.ragdoc.application.chat.agent;

/**
 * PR-6b / EMS-PR6 §4.5: 预算突破异常。BudgetManager.evaluate 在 hard-budget 维度超出时由 Executor 包装成
 * RuntimeException 终止执行; 或调用方先 check 决定短路。
 */
public class BudgetExceededException extends RuntimeException {

    public final BudgetDimension dimension;

    public BudgetExceededException(BudgetDimension dimension, String message) {
        super(message);
        this.dimension = dimension == null ? BudgetDimension.MAX_STEPS : dimension;
    }
}
