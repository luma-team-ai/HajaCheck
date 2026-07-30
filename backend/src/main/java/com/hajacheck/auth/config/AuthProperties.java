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

    /**
     * 사용자 초대 코드 TTL(#794). 기본 180초 — 발급 모달의 카운트다운(프론트)과 동일해야 한다.
     * 1회용(redeem 시 즉시 삭제)이라 짧게 둘수록 발급 화면 캡처·전달 지연으로 인한 도용 창이 좁아진다.
     */
    private Duration inviteCodeTtl = Duration.ofSeconds(180);

    /** 비밀번호 재설정 1단계 rate-limit(2단계엔 걸지 않는다 — 계약 §Rate-limit). */
    private PasswordResetRateLimit passwordResetRateLimit = new PasswordResetRateLimit();

    /** 사업자등록증 OCR 공개 프록시 rate-limit(#557 / HAJA-324). */
    private BusinessLicenseOcrRateLimit businessLicenseOcrRateLimit = new BusinessLicenseOcrRateLimit();

    /** 초대 코드 redeem rate-limit(#794, PR머신 리뷰 P2 — 무차별 대입 방어). */
    private InviteCodeRedeemRateLimit inviteCodeRedeemRateLimit = new InviteCodeRedeemRateLimit();

    /** 로그인 후 비밀번호 변경 rate-limit(#1315 — 탈취된 세션의 현재 비밀번호 무차별 대입 방어). */
    private PasswordChangeRateLimit passwordChangeRateLimit = new PasswordChangeRateLimit();

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

    /**
     * 사업자등록증 OCR 공개 프록시 rate-limit 설정(#557 / HAJA-324) — 비로그인(가입 전) 엔드포인트라
     * PasswordResetRateLimit 처럼 사용자 축(이메일)이 존재하지 않는다. 축은 <b>전역 상한</b> 하나뿐이다.
     *
     * <p>⚠️ <b>IP 축을 쓰지 않는 이유는 PasswordResetRateLimit 과 동일</b>(2026-07-17 A 결정) —
     * nginx 가 {@code X-Forwarded-For} 에 클라 제공값을 덧붙이고 스프링이 첫 항목을 클라 IP 로 채택해
     * 헤더 위조로 무력화되며, 실제 엣지가 레포 밖 host nginx 라 레포만 고쳐선 완결되지 않는다.
     *
     * <p><b>목적</b>: 이 축의 방어 대상은 특정 사용자가 아니라 AI 서버(RapidOCR+LLM, CPU 부하가 큰
     * 다운스트림) 자체다 — 비로그인이라 인증된 사용자 축도 없어 "전역 상한 = 유일한 방어선"이다.
     *
     * <p>⚠️ <b>알려진 한계</b>: 클라이언트별 공정성이 없어 상한에 닿으면 그 순간의 모든 요청(공격자든
     * 정상 사용자든)이 함께 429가 된다. 근본 해결(클라이언트 축·CAPTCHA 등)은 후속 이슈로 분리한다
     * (#557 구현 범위 — PasswordResetRateLimit 의 "근본 해결은 후속 이슈" 메모와 동일 기조).
     */
    public static class BusinessLicenseOcrRateLimit {

        /** 전역 상한(전체 합산) 허용 횟수. 기본 분당 20회 — 정상 가입 트래픽 대비 여유를 두되, AI 서버 CPU를 지킨다. */
        private int globalLimit = 20;

        /** 전역 상한 창 길이. */
        private Duration globalWindow = Duration.ofMinutes(1);

        /**
         * 일일 절대 캡(security-reviewer P1, #557) — 분당 상한만으로는 <b>지속 반복 시 일일 LLM 호출량이
         * 무제한</b>이다(20/분 × 1440분 = 최대 28,800/일). 비로그인·무마찰 엔드포인트가 유료 LLM
         * 다운스트림을 호출하므로, 순간 CPU를 캡하는 분당 축과 별개로 <b>일일 과금 총량</b>을 캡하는
         * 축을 하나 더 둔다. 분당 캡과 마찬가지로 전역(요청자 구분 없음) — 이유는 위 globalLimit 문서와
         * 동일(IP 축 미사용). 기본 500/일 — 정상 가입 트래픽(하루 두 자릿수 수준 추정) 대비 여유를 두되,
         * 무제한 방지가 목적이라 크게 잡지 않는다.
         */
        private int dailyLimit = 500;

        /** 일일 캡 창 길이. */
        private Duration dailyWindow = Duration.ofDays(1);

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

        public int getDailyLimit() {
            return dailyLimit;
        }

        public void setDailyLimit(int dailyLimit) {
            this.dailyLimit = dailyLimit;
        }

        public Duration getDailyWindow() {
            return dailyWindow;
        }

        public void setDailyWindow(Duration dailyWindow) {
            this.dailyWindow = dailyWindow;
        }
    }

    /**
     * 초대 코드 redeem rate-limit 설정(#794) — 이미 로그인된(WAITING) 사용자가 호출하는 엔드포인트라
     * 비밀번호 재설정(비로그인, 이메일 축)과 달리 <b>사용자(userId) 축</b> 하나로 충분하다.
     * 코드 공간(32^6 ≈ 8.8억)·TTL 180초로 단발 성공 확률은 극히 낮지만, 무제한 반복 시도를 막아
     * 온라인 브루트포스의 실익을 없앤다. 기본 5분 5회 — 정상 사용자의 오타 재시도는 통과시키는 선.
     */
    public static class InviteCodeRedeemRateLimit {

        private int userLimit = 5;

        private Duration userWindow = Duration.ofMinutes(5);

        public int getUserLimit() {
            return userLimit;
        }

        public void setUserLimit(int userLimit) {
            this.userLimit = userLimit;
        }

        public Duration getUserWindow() {
            return userWindow;
        }

        public void setUserWindow(Duration userWindow) {
            this.userWindow = userWindow;
        }
    }

    /**
     * 로그인 후 비밀번호 변경 rate-limit 설정(#1315) — 이미 로그인된 사용자가 호출하는 엔드포인트라
     * 초대 코드 redeem(InviteCodeRedeemRateLimit)과 같이 <b>사용자(userId) 축</b> 하나로 충분하다
     * (비로그인 재설정의 이메일 축·전역 상한과 달리 계정 열거·메일 폭탄 축이 존재하지 않는다).
     * IP 축을 쓰지 않는 이유는 {@code auth.support.RateLimiter} javadoc 과 동일(2026-07-17 A 결정).
     *
     * <p><b>방어 대상</b>: 세션이 탈취된 상황(XSS·기기 방치)에서 공격자가 <b>현재 비밀번호를 무차별 대입</b>해
     * 계정을 완전히 인수하는 것. 현재 비밀번호 확인이 이 엔드포인트의 유일한 재인증 관문이므로, 그 관문에
     * 시도 횟수 제한이 없으면 관문이 사실상 없는 것과 같다.
     *
     * <p>기본 5분 5회 — 정상 사용자의 오타 재시도는 통과시키되(로그인 자체는 별도 축이라 영향 없음),
     * 온라인 브루트포스의 실익은 없애는 선. 성공·실패를 가리지 않고 카운트하므로 상한에 닿으면 창이
     * 지날 때까지 변경이 막힌다(자기 계정 한정이라 타 사용자 영향 없음).
     */
    public static class PasswordChangeRateLimit {

        private int userLimit = 5;

        private Duration userWindow = Duration.ofMinutes(5);

        public int getUserLimit() {
            return userLimit;
        }

        public void setUserLimit(int userLimit) {
            this.userLimit = userLimit;
        }

        public Duration getUserWindow() {
            return userWindow;
        }

        public void setUserWindow(Duration userWindow) {
            this.userWindow = userWindow;
        }
    }

    public PasswordChangeRateLimit getPasswordChangeRateLimit() {
        return passwordChangeRateLimit;
    }

    public void setPasswordChangeRateLimit(PasswordChangeRateLimit passwordChangeRateLimit) {
        this.passwordChangeRateLimit = passwordChangeRateLimit;
    }

    public PasswordResetRateLimit getPasswordResetRateLimit() {
        return passwordResetRateLimit;
    }

    public void setPasswordResetRateLimit(PasswordResetRateLimit passwordResetRateLimit) {
        this.passwordResetRateLimit = passwordResetRateLimit;
    }

    public BusinessLicenseOcrRateLimit getBusinessLicenseOcrRateLimit() {
        return businessLicenseOcrRateLimit;
    }

    public void setBusinessLicenseOcrRateLimit(BusinessLicenseOcrRateLimit businessLicenseOcrRateLimit) {
        this.businessLicenseOcrRateLimit = businessLicenseOcrRateLimit;
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

    public Duration getInviteCodeTtl() {
        return inviteCodeTtl;
    }

    public void setInviteCodeTtl(Duration inviteCodeTtl) {
        this.inviteCodeTtl = inviteCodeTtl;
    }

    public InviteCodeRedeemRateLimit getInviteCodeRedeemRateLimit() {
        return inviteCodeRedeemRateLimit;
    }

    public void setInviteCodeRedeemRateLimit(InviteCodeRedeemRateLimit inviteCodeRedeemRateLimit) {
        this.inviteCodeRedeemRateLimit = inviteCodeRedeemRateLimit;
    }
}
