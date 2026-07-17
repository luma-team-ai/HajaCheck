package com.hajacheck.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 기업 인증 설정 — 토큰 TTL(비밀번호 재설정/가입 상태) + 비밀번호 재설정 rate-limit.
 * "10m", "30d" 형태 문자열이 Duration 으로 바인딩된다.
 */
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /**
     * 비밀번호 재설정 토큰 TTL. 기본 10분 — 메일 링크의 유효시간이자 보조 인덱스 TTL(#194 / HAJA-172).
     * 짧게 둘수록 유출 링크의 악용 창이 좁아진다. 메일 본문 안내 문구도 이 값을 그대로 쓴다.
     */
    private Duration passwordResetTtl = Duration.ofMinutes(10);

    /** 가입 상태 조회 토큰 TTL. 기본 30일. */
    private Duration signupStatusTtl = Duration.ofDays(30);

    /** 비밀번호 재설정 1단계 rate-limit(2단계엔 걸지 않는다 — 계약 §Rate-limit). */
    private PasswordResetRateLimit passwordResetRateLimit = new PasswordResetRateLimit();

    /**
     * 비밀번호 재설정 요청 rate-limit 설정. 축은 <b>대상 이메일</b>과 <b>전역 상한</b> 둘뿐이다(IP 축 미사용).
     */
    public static class PasswordResetRateLimit {

        /**
         * 대상 이메일당 허용 횟수. <b>사용자 단위 실질 방어는 이 축</b>(특정 피해자 메일 폭탄 차단).
         * 기본 5분 3회 — 정상 사용자의 재시도(오타·메일 지연 체감)는 통과하고 폭탄은 막는 선.
         */
        private int emailLimit = 3;

        /** 이메일 축 창 길이. */
        private Duration emailWindow = Duration.ofMinutes(5);

        /**
         * 전역 상한(전체 합산).
         *
         * <p>⚠️ <b>성격</b>: SMTP 제공자 한도·발신 도메인 평판을 지키는 <b>천장</b>이지 사용자 단위 방어가 아니다.
         * 사용자 방어는 위 이메일 축이 한다. 따라서 <b>정상 트래픽이 절대 닿지 않을 만큼 크게</b> 잡는다.
         *
         * <p><b>기본 60/분 산정 근거</b>: ①제공자 쿼터 — 분당 60건(≈1 msg/s)은 일반적인 SMTP 제공자의
         * 처리율 한도(예: AWS SES 기본 14 msg/s ≈ 840/분) 한참 아래라 제공자 차단·평판 훼손을 유발하지 않는다.
         * ②정상 트래픽 대비 — 이 서비스의 비밀번호 재설정은 하루 한 자릿수 수준이라 분당 60은 수백 배 여유다.
         * 제공자 확정 후 그 쿼터에 맞춰 재산정할 것(설정값이므로 배포 없이 조정 가능).
         *
         * <p>⚠️ <b>알려진 한계</b>: IP 축이 없어 공격자와 정상 사용자를 구분할 수 없으므로, 공격자가 임의
         * 이메일로 이 상한을 채우면 그 창의 정상 요청도 429 가 된다(DoS 로 역이용). N 을 크게 잡는 건
         * 그 실익을 줄이기 위함이기도 하다. 근본 해결(XFF 신뢰경계 정리 후 IP 축·CAPTCHA)은 후속 이슈.
         */
        private int globalLimit = 60;

        /** 전역 상한 창 길이. */
        private Duration globalWindow = Duration.ofMinutes(1);

        public int getEmailLimit() {
            return emailLimit;
        }

        public void setEmailLimit(int emailLimit) {
            this.emailLimit = emailLimit;
        }

        public Duration getEmailWindow() {
            return emailWindow;
        }

        public void setEmailWindow(Duration emailWindow) {
            this.emailWindow = emailWindow;
        }

        public int getGlobalLimit() {
            return globalLimit;
        }

        public void setGlobalLimit(int globalLimit) {
            this.globalLimit = globalLimit;
        }

        public Duration getGlobalWindow() {
            return globalWindow;
        }

        public void setGlobalWindow(Duration globalWindow) {
            this.globalWindow = globalWindow;
        }
    }

    public PasswordResetRateLimit getPasswordResetRateLimit() {
        return passwordResetRateLimit;
    }

    public void setPasswordResetRateLimit(PasswordResetRateLimit passwordResetRateLimit) {
        this.passwordResetRateLimit = passwordResetRateLimit;
    }

    public Duration getPasswordResetTtl() {
        return passwordResetTtl;
    }

    public void setPasswordResetTtl(Duration passwordResetTtl) {
        this.passwordResetTtl = passwordResetTtl;
    }

    public Duration getSignupStatusTtl() {
        return signupStatusTtl;
    }

    public void setSignupStatusTtl(Duration signupStatusTtl) {
        this.signupStatusTtl = signupStatusTtl;
    }
}
