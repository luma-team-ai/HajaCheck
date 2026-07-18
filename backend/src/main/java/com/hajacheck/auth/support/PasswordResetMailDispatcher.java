package com.hajacheck.auth.support;

import com.hajacheck.global.config.AsyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 재설정 메일을 <b>비동기로</b> 발송하고, 실패를 호출자에게 전파하지 않는 경계.
 *
 * <p>⚠️ <b>{@link Async} 를 제거하지 말 것 — 계정 열거 취약점이 된다.</b> 동기 발송하면 계정이 존재하는
 * 요청만 SMTP 왕복(수백 ms~수 초)만큼 느려져, 응답 바디가 같아도 <b>응답시간 차이로 계정 존재를 열거</b>할 수 있다.
 * 1단계는 존재 여부와 무관하게 동일 응답 + 동일 응답시간이어야 한다.
 *
 * <p>⚠️ <b>실패를 삼키는 것도 의도된 설계다</b>. 발송 실패를 응답에 반영하면 그 자체가 계정 존재 단서가 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordResetMailDispatcher {

    private final PasswordResetMailSender mailSender;

    /**
     * 발송을 메일 전용 스레드 풀에 위임한다(즉시 반환).
     *
     * <p>큐가 포화돼도 <b>예외가 요청 스레드로 전파되지 않는다</b> — 실행기의 거부 핸들러가 삼키고 WARN 만
     * 남기도록 구성돼 있다({@link AsyncConfig#mailTaskExecutor()}). 전파되면 존재하는 계정만 500 이 되어
     * 그 자체로 계정 열거 단서가 된다.
     *
     * @param emailHash 감사 로그용 이메일 해시 — 원문 이메일은 로그에 남기지 않는다.
     */
    @Async(AsyncConfig.MAIL_TASK_EXECUTOR)
    public void dispatch(String toEmail, String resetLink, String emailHash) {
        try {
            mailSender.send(toEmail, resetLink);
            log.info("비밀번호 재설정 메일 발송 성공 — emailHash={}", emailHash);
        } catch (Exception e) {
            // ⚠️ 예외 객체/메시지를 로깅하지 않는다: SMTP 예외 메시지엔 수신자 주소가 그대로 박히는 경우가 흔해
            // (예: "Invalid Addresses ... 550 5.1.1 <user@example.com>") 이메일 원문 로깅 금지에 걸린다.
            // 원인 규명엔 예외 타입 + emailHash 로 충분하다.
            log.warn("비밀번호 재설정 메일 발송 실패 — emailHash={} exception={}",
                    emailHash, e.getClass().getName());
        }
    }
}
