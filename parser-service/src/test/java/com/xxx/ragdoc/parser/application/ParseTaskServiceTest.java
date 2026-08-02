package com.xxx.ragdoc.parser.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.xxx.ragdoc.application.document.port.ParseTaskRepository;
import com.xxx.ragdoc.domain.document.ParseTask;
import com.xxx.ragdoc.domain.document.ParseTaskStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * ParseTaskService 状态迁移 invariant 单测(spec §3.3)。
 *
 * <p>不依赖 Spring, mock repository + fixed Clock 验证所有合法/非法迁移分支。
 */
class ParseTaskServiceTest {

    private ParseTaskRepository repo;
    private ParseTaskService service;
    private final Clock fixedClock =
            Clock.fixed(Instant.parse("2026-08-02T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setup() {
        repo = Mockito.mock(ParseTaskRepository.class);
        // update() 返回 void, mock 默认 no-op, 无需 doNothing
        service = new ParseTaskService(repo, fixedClock);
        // retryDelaySeconds 默认 60, 反射注入
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "retryDelaySeconds", 60L);
    }

    private ParseTask running(int retry, int maxRetry, int chunksWritten) {
        return new ParseTask(
                1L,
                100L,
                "abc",
                ParseTaskStatus.RUNNING,
                retry,
                maxRetry,
                chunksWritten,
                0,
                null,
                null,
                List.of(),
                Instant.now(fixedClock),
                "worker-1",
                Instant.now(fixedClock),
                Instant.now(fixedClock));
    }

    private ParseTask failed(int retry, int maxRetry) {
        return new ParseTask(
                1L,
                100L,
                "abc",
                ParseTaskStatus.FAILED,
                retry,
                maxRetry,
                5,
                0,
                "err",
                "java.lang.RuntimeException",
                List.of(),
                Instant.now(fixedClock),
                "worker-1",
                Instant.now(fixedClock),
                Instant.now(fixedClock));
    }

    // ===== markParsed =====

    @Test
    void markParsed_running_then_terminal() {
        ParseTask task = running(0, 3, 10);
        ParseTask r = service.markParsed(task);
        assertThat(r.status()).isEqualTo(ParseTaskStatus.PARSED);
        verify(repo).update(any());
    }

    @Test
    void markParsed_guard_rejects_zero_chunks() {
        ParseTask task = running(0, 3, 0);
        assertThatThrownBy(() -> service.markParsed(task))
                .isInstanceOf(ParseTaskService.IllegalStateTransition.class)
                .hasMessageContaining("chunks_written<=0");
    }

    @Test
    void markParsed_rejects_non_running() {
        ParseTask task = failed(0, 3);
        assertThatThrownBy(() -> service.markParsed(task))
                .isInstanceOf(ParseTaskService.IllegalStateTransition.class);
    }

    // ===== markFailed =====

    @Test
    void markFailed_retry_available_then_failed_with_delay() {
        ParseTask task = running(0, 3, 0);
        ParseTask r = service.markFailed(task, new RuntimeException("boom"));
        assertThat(r.status()).isEqualTo(ParseTaskStatus.FAILED);
        assertThat(r.retryCount()).isEqualTo(1);
        // visibleAt = now + 60s
        assertThat(r.visibleAt()).isEqualTo(Instant.parse("2026-08-02T10:01:00Z"));
    }

    @Test
    void markFailed_max_retries_reached_then_cancelled() {
        ParseTask task = running(2, 3, 0);
        ParseTask r = service.markFailed(task, new RuntimeException("boom"));
        assertThat(r.status()).isEqualTo(ParseTaskStatus.CANCELLED);
        assertThat(r.retryCount()).isEqualTo(3);
    }

    @Test
    void markFailed_appends_attempt_record() {
        ParseTask task = running(0, 3, 0);
        ParseTask r = service.markFailed(task, new RuntimeException("boom"));
        assertThat(r.attempts()).hasSize(1);
        assertThat(r.attempts().get(0).errorClass()).isEqualTo("java.lang.RuntimeException");
    }

    // ===== checkpoint =====

    @Test
    void checkpoint_running_then_flush_in_place() {
        ParseTask task = running(0, 3, 0);
        ParseTask r = service.checkpoint(task, 10, 10);
        assertThat(r.status()).isEqualTo(ParseTaskStatus.RUNNING);
        assertThat(r.chunksWritten()).isEqualTo(10);
        assertThat(r.chunkSeqOffset()).isEqualTo(10);
        ArgumentCaptor<ParseTask> captor = ArgumentCaptor.forClass(ParseTask.class);
        verify(repo).update(captor.capture());
        assertThat(captor.getValue().chunksWritten()).isEqualTo(10);
    }

    @Test
    void checkpoint_rejects_non_running() {
        ParseTask task = failed(0, 3);
        assertThatThrownBy(() -> service.checkpoint(task, 10, 10))
                .isInstanceOf(ParseTaskService.IllegalStateTransition.class);
    }

    // ===== requeueFromFailed =====

    @Test
    void requeueFromFailed_then_pending_clears_lease() {
        ParseTask task = failed(0, 3);
        ParseTask r = service.requeueFromFailed(task);
        assertThat(r.status()).isEqualTo(ParseTaskStatus.PENDING);
        assertThat(r.leasedBy()).isNull();
        assertThat(r.visibleAt()).isEqualTo(Instant.parse("2026-08-02T10:00:00Z"));
    }

    @Test
    void requeueFromFailed_at_max_rejects() {
        ParseTask task = failed(3, 3);
        assertThatThrownBy(() -> service.requeueFromFailed(task))
                .isInstanceOf(ParseTaskService.IllegalStateTransition.class)
                .hasMessageContaining("retry_count>=max_retries");
    }

    @Test
    void requeueFromFailed_wrong_state_rejects() {
        ParseTask task = running(0, 3, 5);
        assertThatThrownBy(() -> service.requeueFromFailed(task))
                .isInstanceOf(ParseTaskService.IllegalStateTransition.class);
    }
}
