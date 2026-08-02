package com.xxx.ragdoc.parser.infrastructure;

import com.xxx.ragdoc.application.document.port.ParseTaskRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 心跳回收 job(spec §3.3 + §8 Commit 3).
 *
 * <p>每 30 秒扫一次 parse_tasks 表: 把 status='RUNNING' + visible_at &lt; now 的 task 回滚 PENDING, 清
 * leasedBy, 让别的 worker 重新 lease. 解决 kill -9 / OOM / 进程崩溃留下的 zombie worker(spec §5.1 kill -9 故障路径).
 *
 * <p>覆盖 DoD-1: parser 进程死, 重启后该 job 周期性扫, 把 zombie RUNNING 还回 PENDING → 下轮 worker pull 重启继续解析。续点字段
 * chunks_written / chunk_seq_offset 在 worker 重启后从该值继续(spec §3.1).
 *
 * <p>DoD-2 重试队列: FAILED → PENDING 的回 rebalance 不是本 job 责任, 失败时 markFailed 已经把 visibleAt push +60s(见
 * ParseTaskService.markFailed); 本 job 第二轮发现 PENDING 状态时, 由 ParseTaskConsumer 拿到 RocketMQ 原始 message
 * 重投 OR 通过心跳找到 PENDING 重发 MQ(后续 V3.5 加重生投 job).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisibilityTimeoutScheduler {

    private final ParseTaskRepository parseTaskRepository;
    private final Clock clock;

    /** 心跳周期, 默认 30s. spec §8 Commit 3 + §4.2 kill -9 演练期望回收延迟在 30-60s 级别. */
    @Value("${rag.parser.reap-interval-ms:30000}")
    private long reapIntervalMs;

    /** spec §4.2: 每 30s(@Scheduled fixedRate) 扫过期 RUNNING. */
    @Scheduled(fixedDelayString = "${rag.parser.reap-interval-ms:30000}")
    public void reapExpiredRunningTasks() {
        Instant now = Instant.now(clock);
        try {
            int affected = parseTaskRepository.reapExpiredRunning(now);
            // PM-V3-B 演练调试: 每次循环都 INFO 留痕, 防 heartbeat job 真挂了看不出。
            // 默认 line 数稳定后可改回 debug。
            if (affected > 0) {
                log.info(
                        "parse_reaper.reaped count={} (zombie RUNNING → PENDING, < {})",
                        affected,
                        now);
            } else {
                log.info("parse_reaper.tick no_reap_at={}", now);
            }
        } catch (Exception e) {
            log.error("parse_reaper.cycle_failed err={}", e.getMessage(), e);
        }
    }
}
