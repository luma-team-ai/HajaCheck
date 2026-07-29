package com.hajacheck.membership.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 플랜 하향 예약 실행 배치({@code ScheduledPlanChangeScheduler}) 설정(#1105 / HAJA-526) —
 * SpringBoot_코드_컨벤션.md §9: 매직넘버 금지.
 *
 * <p><b>{@code PlanExpiryProperties} 와 달리 기본값이 "켜짐"인 이유</b>: 만료 강등 배치는 V27 백필이
 * 만든 <b>추정 만료일</b> 때문에 켜는 순간 기존 유료 회사가 일괄 강등되는 위험이 있어 4중 가드(기동
 * 스위치·DRY_RUN·컷오프·상한)를 뒀다. 이 배치의 대상은 그런 소급 데이터가 아니라 <b>사람이 방금 명시적으로
 * 만든 예약 행</b>뿐이라(마이그레이션 시점의 기존 행 자체가 0건이다) 소급 대량 실행이라는 표면이 없다.
 * 반대로 기본을 꺼 두면 "신청은 받아 놓고 영원히 적용되지 않는" 상태가 되어, 사용자는 하향한 줄 알지만
 * 요금 기준은 그대로인 더 나쁜 결과가 된다.
 *
 * <p>다만 사람 없이 계정을 정지시키는 배치라는 점은 같으므로, <b>비상 정지 스위치</b>({@link #isEnabled()}
 * = false)와 <b>1회 실행 상한</b>({@link #getMaxPerRun()})은 그대로 둔다.
 */
@Validated
@ConfigurationProperties(prefix = "hajacheck.plan.scheduled-change")
public class ScheduledPlanChangeProperties {

    /**
     * 기동 스위치 — <b>기본 true</b>. false면 스케줄러가 트리거돼도 대상 조회조차 하지 않고 즉시
     * 반환한다(사고 시 배포 없이 배치만 끄기 위한 비상 스위치).
     */
    private boolean enabled = true;

    /**
     * 1회 실행당 적용 상한 — 기본 100. 초과하면 <b>아무것도 하지 않고</b> 중단한다(부분 적용도 만들지
     * 않는다). 이 배치의 대상은 사람이 만든 예약뿐이라 정상 운영에서 100건을 넘길 일이 없고, 넘겼다면
     * 그 자체가 사고 신호(예약 생성 경로 오류·시각 판정 오류)이므로 사람을 부르는 편이 안전하다.
     */
    @Min(1)
    private int maxPerRun = 100;

    /**
     * 예약·구독 행 잠금 대기 상한(ms) — 기본 3000. 0 이면 무제한 대기(권장하지 않음).
     *
     * <p>{@code @Scheduled} 기본 스레드 풀은 1개인데 이 앱에는 스케줄러가 여럿이다. 한 건의 잠금 대기가
     * 무한정 늘어지면 그 스레드가 묶여 <b>다른 배치까지 통째로 멈춘다</b>({@code PlanExpiryProperties
     * #getLockTimeoutMs()} 와 같은 이유·같은 적용 방식 — JPA lock.timeout 힌트는 PostgreSQL 에서 무시되므로
     * {@code UserPlanRepository#applyLockTimeout} 의 트랜잭션 로컬 {@code set_config} 를 재사용한다).
     */
    @Min(0)
    private int lockTimeoutMs = 3000;

    /**
     * 유료→유료 하향(#1177 C안)의 <b>미결제 유예 일수</b> — 기본 7일, 최대 14일.
     *
     * <p>예약 적용 시점에 유료 대상 구독을 발급하되 결제는 아직 없으므로, 1개월이 아니라 이 일수만큼만
     * 결제 주기를 열고 {@code user_plans.payment_pending_until} 을 세운다
     * ({@code UserPlan#startPaymentGracePeriod}). 그 안에 결제하면 정상 1개월 주기가 시작되고, 넘기면
     * 이 배치의 <b>2단계</b>가 FREE 로 강등한다.
     *
     * <p><b>⚠️ {@code @Max} 가 필요한 이유(리뷰 P1 정정)</b>: 처음에는 "유예 중 한도가 FREE 라 값을 키워도
     * 무상 혜택은 늘지 않는다"며 상한을 두지 않았는데, 그 근거는 <b>거짓이었다</b>. 유예 중 낮아지는 것은
     * 요금제 객체 전체이지만 그것을 경유하지 않는 경로가 있으면 그만큼 유료 기능이 열리고, 실제로 1차
     * 구현에서 상담사 연결·AI 부가기능이 그랬다. 게다가 유예 기간은 <b>어떤 경우에도 청구가 발생하지 않는
     * 구간</b>이므로 길수록 미결제 상태의 노출이 커진다(정지된 좌석·중단된 서비스로 사용자가 방치되는
     * 시간도 함께 늘어난다). 그래서 "결제할 시간으로 합리적인 최대치"인 2주로 못박는다.
     *
     * <p>{@code @Min(1)} — 0이면 발급 즉시 만료라 유예의 의미가 없고, 같은 회차 안에서 발급(유예 진입
     * 알림)과 강등(만료 알림)이 겹쳐 사용자가 알림 두 건을 동시에 받는다.
     *
     * <p>⚠️ 이 값은 <b>발급 시점에만</b> 쓰인다. 이미 발급된 유예 구독의 마감은
     * {@code user_plans.payment_pending_until} 에 굳어 있으므로, 운영 중 이 값을 바꿔도 진행 중인 유예가
     * 소급해 늘거나 줄지 않는다(유예 판정 자체도 이 값에 의존하지 않는다 — {@code PaymentGraceService}).
     */
    @Min(1)
    @Max(14)
    private int paymentGraceDays = 7;

    /**
     * 유료 대상 하향 예약 허용 스위치(#1177) — <b>기본 false(닫힘)</b>.
     *
     * <p>백엔드는 유료→유료 하향을 지원하지만 프론트에는 아직 그 UI(결제 마감 안내·유예 배너·결제 유도)가
     * 없다. 그 공백에서 API 로 직접 예약을 넣으면 사용자는 <b>안내를 한 번도 받지 못한 채</b> 유예 진입
     * 시점의 좌석 정지와 유예 만료 시점의 FREE 강등을 맞는다 — 사람 없는 배치가 권한을 내리는 기능이라
     * "모르는 사이 당하는" 경로를 열어 두면 안 된다.
     *
     * <p>false 이면 {@code AdminPlanService#scheduleChange} 가 유료 대상을 기존과 동일하게
     * {@code PLAN_SCHEDULE_PAID_TARGET_UNSUPPORTED} 로 거절한다(프론트의 기존 안내 UI 가 그대로 동작).
     * 프론트 연동이 끝나면 <b>배포 없이</b> 이 값만 뒤집는다.
     */
    private boolean paidTargetEnabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxPerRun() {
        return maxPerRun;
    }

    public void setMaxPerRun(int maxPerRun) {
        this.maxPerRun = maxPerRun;
    }

    public int getLockTimeoutMs() {
        return lockTimeoutMs;
    }

    public void setLockTimeoutMs(int lockTimeoutMs) {
        this.lockTimeoutMs = lockTimeoutMs;
    }

    public int getPaymentGraceDays() {
        return paymentGraceDays;
    }

    public void setPaymentGraceDays(int paymentGraceDays) {
        this.paymentGraceDays = paymentGraceDays;
    }

    public boolean isPaidTargetEnabled() {
        return paidTargetEnabled;
    }

    public void setPaidTargetEnabled(boolean paidTargetEnabled) {
        this.paidTargetEnabled = paidTargetEnabled;
    }
}
