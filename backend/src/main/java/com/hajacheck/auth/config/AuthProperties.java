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

    /** 사업자등록증 ↔ 입력 사업자번호 대조 게이트 rate-limit(#1469 / HAJA-650) — 위 프리필 캡과 별개 축. */
    private BusinessLicenseCrossCheckRateLimit businessLicenseCrossCheckRateLimit =
            new BusinessLicenseCrossCheckRateLimit();

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
     * 사업자등록증 대조 게이트 rate-limit 설정(#1469 / HAJA-650) — 가입 제출 시 업로드된 등록증을 OCR로
     * 읽어 입력 사업자번호와 대조하는 {@code BusinessLicenseCrossCheckService} 전용 축이다.
     *
     * <p>⚠️ <b>{@link BusinessLicenseOcrRateLimit}(공개 프리필)과 절대 공유하면 안 된다.</b> 캡을
     * 공유하면 공격자가 비로그인 프리필 API를 반복 호출해 캡을 태우는 것만으로 <b>기업 가입 경로를
     * 조작</b>할 수 있다. 키(prefix)와 이 설정을 모두 분리해 프리필 트래픽이 대조 예산을 건드리지 못하게 한다.
     *
     * <p>IP 축을 쓰지 않는 이유는 {@link BusinessLicenseOcrRateLimit}·{@link PasswordResetRateLimit} 과
     * 동일하다(2026-07-17 A 결정 — XFF 위조 가능).
     *
     * <p><b>축이 3개인 이유(2026-08-04 리뷰 P1)</b>: 전역 축만 두면 공격자가 <b>차단당하는 시도로</b>
     * 캡을 태울 수 있다 — {@code MISMATCH} 차단은 회사·유저 행을 만들지 않아 중복검사가 영원히 통과하고,
     * 같은 요청을 무한 반복할 수 있기 때문이다. 그래서 <b>사업자번호 축(좁은 축)을 전역 축보다 먼저</b>
     * 걸어 한 번호가 전역 예산을 갉아먹지 못하게 한다.
     *
     * <p><b>캡 소진 시 동작</b>: 예전엔 fail-open 통과였으나 지금은 <b>429 차단</b>이다. "캡을 태우면
     * 게이트가 꺼진다"가 성립하면 통제 자체가 무의미해진다 — 관측 가능한 가용성 저하가 무증상 보안
     * 우회보다 낫다는 판단(일일 캡 소진은 {@code log.error} 로 승격돼 관측된다).
     */
    public static class BusinessLicenseCrossCheckRateLimit {

        /**
         * 전역 상한(전체 합산) 허용 횟수. <b>기본 분당 30회</b> — 호출 시점이 "가입 폼 최종 제출"이라
         * 파일 선택마다 발생하는 프리필(20/분)보다 트래픽이 희박하다. 그럼에도 20이 아니라 30으로 둔 이유는
         * 순간 동시 가입(캠페인·시연 등)이 429 로 반려되는 상황을 피하기 위함이다.
         * AI 서버 CPU 관점에서 30/분은 프리필(20/분)과 합쳐도 기존 부하 설계 범위 안이다.
         */
        private int globalLimit = 30;

        /** 전역 상한 창 길이. */
        private Duration globalWindow = Duration.ofMinutes(1);

        /**
         * 일일 절대 캡. <b>기본 1000/일</b> — 분당 축만으로는 지속 반복 시 일일 LLM 호출이 사실상 무제한
         * (30/분 × 1440분 = 43,200/일)이라 총량 캡을 둔다.
         *
         * <p>프리필(500/일)보다 크게 잡은 근거: ①캡 소진의 <b>결과가 다르다</b> — 프리필은 사용자가 수동
         * 입력으로 폴백할 뿐이지만, 여기선 <b>기업 가입 자체가 429로 막힌다</b>. ②정상 가입은 하루 두
         * 자릿수 추정이라 1000은 수십 배 여유다. 그럼에도 무제한이 아니므로 LLM 과금 폭주는 이 선에서 멈춘다.
         *
         * <p>⚠️ <b>과거 산정 근거 정정(2026-08-04)</b>: "공격자가 이 캡을 태우려면 중복검사·진위확인을 먼저
         * 통과해야 해서 무마찰 반복이 불가능하다"고 적었으나 이는 <b>틀렸다</b> — 국세청을 통과하는 실재
         * 사업자 정보는 공개된 1건이면 충분하고, 차단된 시도는 행을 남기지 않아 같은 요청을 무한 반복할 수
         * 있다. 이 축을 실제로 지키는 것은 <b>아래 사업자번호 축</b>이다(한 번호당 {@link #brnLimit}회로
         * 묶어, 전역 캡을 태우려면 서로 다른 유효 사업자번호를 수백 개 동원해야 하게 만든다).
         *
         * <p>⚠️ 이 캡에 실제로 닿으면 {@code log.error} 경보가 남는다 — 값을 올리기 전에 "정상 트래픽 증가"
         * 인지 "가입을 막으려는 시도"인지부터 판별할 것.
         */
        private int dailyLimit = 1000;

        /** 일일 캡 창 길이. */
        private Duration dailyWindow = Duration.ofDays(1);

        /**
         * <b>사업자번호 축</b>(좁은 축) 허용 시도 횟수. 키는 사업자등록번호의 <b>해시</b> 접두다(평문 금지).
         * 전역 축보다 <b>먼저</b> 검사하며, 초과하면 AI 호출 없이 즉시 대조 실패로 차단한다.
         *
         * <p><b>기본 1시간 3회 산정 근거</b>: ①정상 사용자 — 대조가 성공({@code MATCH})하면 카운터를
         * <b>즉시 해제</b>하므로(#1315 비밀번호 변경 성공 시 해제와 같은 패턴) 이 값은 사실상 "연속 실패
         * 허용 횟수"다. 엉뚱한 사진을 올린 사용자가 1~2번 고쳐 올릴 여유는 그대로 남는다. ②공격자 —
         * 한 사업자번호로는 시간당 3회밖에 시도하지 못해, 전역 일일 캡(1000)을 태우려면 국세청을 통과하는
         * 서로 다른 사업자번호를 수백 개 동원해야 한다. ③더 키우면 ②가 무너지고 더 줄이면 ①이 불편해진다 —
         * 3은 그 사이에서 정상 사용자의 재시도 여유를 우선한 값이다.
         */
        private int brnLimit = 3;

        /**
         * 사업자번호 축 창 길이. 기본 1시간 — 분 단위면 공격자가 잠깐 기다렸다 재개하는 비용이 너무 싸고,
         * 하루 단위면 사진을 몇 번 잘못 올린 정상 사용자가 그날 가입을 못 하게 된다.
         */
        private Duration brnWindow = Duration.ofHours(1);

        public int getBrnLimit() {
            return brnLimit;
        }

        public void setBrnLimit(int brnLimit) {
            this.brnLimit = brnLimit;
        }

        public Duration getBrnWindow() {
            return brnWindow;
        }

        public void setBrnWindow(Duration brnWindow) {
            this.brnWindow = brnWindow;
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
     * 온라인 브루트포스의 실익은 없애는 선. <b>변경에 성공하면 카운터를 즉시 해제</b>한다(성공 = 현재
     * 비밀번호를 안다는 증명이라 방어력 손실이 없다) — 그래서 오타 몇 번 뒤 성공한 정상 사용자가 곧바로
     * 재변경을 막히는 일은 없다.
     *
     * <p>⚠️ <b>알려진 한계 — 자기계정 봉쇄(self-account DoS)가 성립한다.</b> 이 축의 키는 {@code userId}
     * 이고, 이 엔드포인트가 방어 대상으로 삼는 상황(세션 탈취)에서 공격자는 이미 피해자 계정 안에 있으므로
     * <b>피해자와 같은 카운터를 공유</b>한다. 공격자가 창마다 한도를 소모시키면 피해자는 계속 429 를 맞아
     * <b>비밀번호를 바꿔 접근을 회수하지 못한다</b>. 즉 "타 사용자에게 영향이 없다"는 맞지만 "DoS 가
     * 성립하지 않는다"는 <b>틀렸다</b> — 정확히 복구 경로를 봉쇄하는 형태의 DoS 다.
     *
     * <p><b>탈출 경로</b>: 이 축에 걸리지 않는 <b>비로그인 이메일 재설정</b>
     * ({@code POST /api/auth/password-reset-request} → 메일 링크)이 남아 있다. rate-limit 축이 이메일·전역이라
     * 완전히 별개이므로 피해자는 그 경로로 비밀번호를 되찾을 수 있다.
     * <b>근본 해결은 전 기기 세션 무효화(#1318)</b> — 탈취 세션 자체를 끊어야 공격자가 카운터를 소모할
     * 수단이 사라진다. 한도를 키우는 것은 해결이 아니다(무차별 대입 방어를 포기하면서 봉쇄 시점을 늦출 뿐).
     */
    public static class PasswordChangeRateLimit {

        private int userLimit = 5;

        private Duration userWindow = Duration.ofMinutes(5);

        /**
         * 침해 의심 경보 임계 — 같은 userId 가 이 횟수를 넘겨 429 를 맞으면 단순 오타가 아니라
         * <b>세션 탈취 후 비밀번호 대입</b>이나 위 자기계정 봉쇄가 진행 중일 가능성이 높다. 그때만 로그를
         * ERROR 로 승격해 정상 사용자의 오타 재시도와 구분한다 — 모든 429 를 같은 WARN 으로 남기면
         * 신호가 잡음에 묻혀 침해를 아무도 알아채지 못한다. 기본 3회.
         */
        private int breachAlertThreshold = 3;

        /** 침해 의심 경보 관찰 창. 기본 1시간 — 사용자 창(5분)보다 길어야 창을 넘나드는 반복이 잡힌다. */
        private Duration breachAlertWindow = Duration.ofHours(1);

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

        public int getBreachAlertThreshold() {
            return breachAlertThreshold;
        }

        public void setBreachAlertThreshold(int breachAlertThreshold) {
            this.breachAlertThreshold = breachAlertThreshold;
        }

        public Duration getBreachAlertWindow() {
            return breachAlertWindow;
        }

        public void setBreachAlertWindow(Duration breachAlertWindow) {
            this.breachAlertWindow = breachAlertWindow;
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

    public BusinessLicenseCrossCheckRateLimit getBusinessLicenseCrossCheckRateLimit() {
        return businessLicenseCrossCheckRateLimit;
    }

    public void setBusinessLicenseCrossCheckRateLimit(
            BusinessLicenseCrossCheckRateLimit businessLicenseCrossCheckRateLimit) {
        this.businessLicenseCrossCheckRateLimit = businessLicenseCrossCheckRateLimit;
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
