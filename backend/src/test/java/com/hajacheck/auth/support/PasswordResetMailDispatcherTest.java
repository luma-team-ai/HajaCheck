package com.hajacheck.auth.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.hajacheck.global.config.AsyncConfig;
import com.hajacheck.support.RecordingPasswordResetMailSender;
import java.lang.reflect.Method;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 발송 디스패처 — "응답을 블로킹하지 않는다"와 "실패가 전파되지 않는다"를 고정한다.
 */
class PasswordResetMailDispatcherTest {

    @Test
    void dispatch_는_메일_전용_실행기에서_비동기로_실행된다() throws Exception {
        // ⚠️ @Async 가 사라지면 발송이 동기로 조용히 실행되어(에러 없이 통과) 존재 계정만 SMTP 왕복만큼
        // 느려진다 → 응답시간으로 계정 열거. 시간 단언은 CI 에서 flaky 하므로 어노테이션 자체를 고정한다.
        Method dispatch = PasswordResetMailDispatcher.class
                .getMethod("dispatch", String.class, String.class, String.class);
        Async async = dispatch.getAnnotation(Async.class);

        assertThat(async)
                .as("dispatch 는 @Async 여야 한다 — 동기 발송은 응답시간 기반 계정 열거를 만든다")
                .isNotNull();
        // 기본 실행기(무한 큐·종료 시 미대기)로 돌아가면 재배포 때 큐의 메일이 조용히 유실된다.
        assertThat(async.value()).isEqualTo(AsyncConfig.MAIL_TASK_EXECUTOR);
    }

    @Test
    void 메일_실행기는_유한_큐와_안전한_거부정책을_쓴다() {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().mailTaskExecutor();
        try {
            // 무한 큐면 SMTP 장애 시 메일이 메모리에 무한정 쌓이고 재배포 때 통째로 사라진다.
            assertThat(executor.getQueueCapacity()).isLessThan(Integer.MAX_VALUE);

            RejectedExecutionHandler handler = executor.getThreadPoolExecutor().getRejectedExecutionHandler();
            // ⚠️ 이 두 정책은 이 PR 이 막은 계정 열거를 되살린다:
            //   CallerRuns → 요청 스레드에서 동기 발송(타이밍 열거)
            //   Abort      → TaskRejectedException 이 요청 스레드로 전파(존재 계정만 500)
            assertThat(handler).isNotInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
            assertThat(handler).isNotInstanceOf(ThreadPoolExecutor.AbortPolicy.class);

            // 거부돼도 호출자에겐 아무 일도 일어나지 않아야 한다(삼키고 WARN 만).
            assertThatCode(() -> handler.rejectedExecution(() -> { }, executor.getThreadPoolExecutor()))
                    .doesNotThrowAnyException();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void 발송_실패는_호출자에게_전파되지_않는다() {
        // 발송 실패가 응답에 반영되면 그 자체가 계정 존재 단서 → 삼키는 것이 의도된 설계.
        RecordingPasswordResetMailSender sender = new RecordingPasswordResetMailSender();
        sender.failWith(new IllegalStateException("SMTP 연결 실패"));
        PasswordResetMailDispatcher dispatcher = new PasswordResetMailDispatcher(sender);

        assertThatCode(() -> dispatcher.dispatch("owner@haja.com", "https://app.test/reset-password?token=x", "hash"))
                .doesNotThrowAnyException();
    }

    @Test
    void 정상_발송은_수신자와_링크를_그대로_전달한다() throws Exception {
        RecordingPasswordResetMailSender sender = new RecordingPasswordResetMailSender();
        PasswordResetMailDispatcher dispatcher = new PasswordResetMailDispatcher(sender);

        dispatcher.dispatch("owner@haja.com", "https://app.test/reset-password?token=abc", "hash");

        RecordingPasswordResetMailSender.Sent sent = sender.awaitSent(java.time.Duration.ofSeconds(1));
        assertThat(sent.toEmail()).isEqualTo("owner@haja.com");
        assertThat(sent.resetLink()).isEqualTo("https://app.test/reset-password?token=abc");
    }
}
