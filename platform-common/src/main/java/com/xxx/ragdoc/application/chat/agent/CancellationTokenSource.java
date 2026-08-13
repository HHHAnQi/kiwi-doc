package com.xxx.ragdoc.application.chat.agent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PR-6b / EMS-PR6 §10: 取消信号源。线程安全。
 *
 * <p>取消通常来自<b>另一线程</b>: SSE 连接、HTTP 客户端 onClose、timeout scheduler。必须用 AtomicBoolean / volatile,
 * 不能按单线程 cancel 假设设计。
 *
 * <p>{@link CancellationToken} 是只读视图 (Run 内部传递防止误调 {@code cancel()})。
 *
 * <p>PR-6b 不实现 cancellation callback / 真正打断远程请求; 仅做 best-effort 协作式取消: 每个 Step 前置关口主动检查。
 */
public final class CancellationTokenSource {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final CancellationToken token = new CancellationToken(this);

    /** 第一次调用返回 true; 已取消再调返回 false。 */
    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public CancellationToken token() {
        return token;
    }

    /** 只读视图。 */
    public static final class CancellationToken {
        private final CancellationTokenSource source;

        private CancellationToken(CancellationTokenSource source) {
            this.source = source;
        }

        public boolean isCancelled() {
            return source.cancelled.get();
        }

        public static CancellationToken never() {
            return NEVER;
        }

        private static final CancellationToken NEVER =
                new CancellationToken(new CancellationTokenSource());
    }
}
