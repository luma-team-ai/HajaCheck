package com.hajacheck.support;

import com.hajacheck.auth.support.PasswordResetMailSender;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 테스트용 발송 스텁 — 발송 내용을 기록하고, 필요하면 실패를 흉내낸다.
 *
 * <p>@Async 발송이라 다른 스레드에서 기록되므로 큐 + 타임아웃 대기로 받는다(발송 "속도"를 단언하는 게 아니라
 * 결과 도착을 기다리는 것 — 시간 기반 단언은 CI 에서 flaky 해지므로 쓰지 않는다).
 */
public class RecordingPasswordResetMailSender implements PasswordResetMailSender {

    public record Sent(String toEmail, String resetLink) {
    }

    private final BlockingQueue<Sent> sent = new LinkedBlockingQueue<>();
    private volatile RuntimeException failure;

    @Override
    public void send(String toEmail, String resetLink) {
        // ⚠️ 기록보다 실패를 먼저 — 순서가 반대면 failWith + awaitSent 조합에서 "발송이 실패했는데 발송됨"으로
        // 관찰돼 테스트가 거짓 통과한다(실패한 발송은 기록되지 않아야 한다).
        RuntimeException toThrow = failure;
        if (toThrow != null) {
            throw toThrow;
        }
        sent.add(new Sent(toEmail, resetLink));
    }

    /** 이후 모든 발송이 이 예외로 실패하게 한다(SMTP 장애 재현). */
    public void failWith(RuntimeException exception) {
        this.failure = exception;
    }

    /** 발송이 도착할 때까지 최대 timeout 대기. 없으면 null. */
    public Sent awaitSent(Duration timeout) throws InterruptedException {
        return sent.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 발송이 "없어야 함"을 확인할 때 사용(짧게 대기 후 비어있으면 통과). */
    public boolean nothingSentWithin(Duration timeout) throws InterruptedException {
        return sent.poll(timeout.toMillis(), TimeUnit.MILLISECONDS) == null;
    }

    public void reset() {
        sent.clear();
        failure = null;
    }
}
