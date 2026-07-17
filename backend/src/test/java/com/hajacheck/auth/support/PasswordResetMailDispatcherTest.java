package com.hajacheck.auth.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.hajacheck.support.RecordingPasswordResetMailSender;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;

/**
 * 발송 디스패처 — "응답을 블로킹하지 않는다"와 "실패가 전파되지 않는다"를 고정한다.
 */
class PasswordResetMailDispatcherTest {

    @Test
    void dispatch_는_비동기여야_한다() throws Exception {
        // ⚠️ @Async 가 사라지면 발송이 동기로 조용히 실행되어(에러 없이 통과) 존재 계정만 SMTP 왕복만큼
        // 느려진다 → 응답시간으로 계정 열거. 시간 단언은 CI 에서 flaky 하므로 어노테이션 자체를 고정한다.
        Method dispatch = PasswordResetMailDispatcher.class
                .getMethod("dispatch", String.class, String.class, String.class);

        assertThat(dispatch.isAnnotationPresent(Async.class))
                .as("dispatch 는 @Async 여야 한다 — 동기 발송은 응답시간 기반 계정 열거를 만든다")
                .isTrue();
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
