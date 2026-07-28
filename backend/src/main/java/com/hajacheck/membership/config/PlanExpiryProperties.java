package com.hajacheck.membership.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 구독 결제 주기 만료 → FREE 자동 강등 배치({@code PlanExpiryScheduler}) 설정(#1145 / HAJA-549) —
 * SpringBoot_코드_컨벤션.md §9: 매직넘버 금지.
 *
 * <p>⚠️ <b>이 값들은 "설정"이 아니라 안전장치다.</b> 이 배치는 사람 개입 없이 유료 권한을 내리고 좌석을
 * 정지시킨다. 게다가 <b>잘못 강등된 회사는 제품 안에서 되돌릴 수 없다</b> —
 * {@code AdminPlanService#changePlan} 이 무결제 상향을 {@code requireNotUpgrade} 로 차단(#988)하므로
 * FREE→STANDARD 복구 경로가 막혀 있고, 운영자가 DB를 직접 만져야 한다.
 *
 * <p><b>왜 이렇게까지 보수적인가(#1145 §2-1)</b>: V27 백필은 {@code current_period_end} 를 실제 결제일이
 * 아니라 {@code started_at + 1개월} 이라는 <b>파생 추정치</b>로 채웠다. 그래서 가입한 지 한 달이 넘은
 * 유료 구독은 이미 전부 "만료" 상태로 DB에 들어 있다. 아무 가드 없이 배포하면 첫 실행에서 기존 유료
 * 회사가 일괄 FREE로 강등되고 좌석까지 정지된다.
 *
 * <p><b>4중 가드와 그 관계</b>(앞의 것이 뒤의 것보다 먼저 판정된다):
 * <ol>
 *   <li>{@link #isEnabled()} = false(기본) → <b>아무것도 하지 않는다</b>(대상 조회조차 안 함).</li>
 *   <li>{@link #getMode()} = {@link Mode#DRY_RUN}(기본) → 대상 조회·집계·대상 id 로그까지만 하고
 *       <b>단 한 건도 강등하지 않는다</b>. 운영자가 로그로 대상을 눈으로 확인한 뒤 ENFORCE 로 올린다.</li>
 *   <li>{@link #getNotBefore()} → 설정되면 그보다 이른 {@code current_period_end} 는 <b>쿼리 단계에서</b>
 *       대상에서 빠진다. V27 백필 추정치로 채워진 과거 구간을 코드가 직접 배제하는 컷오프다 —
 *       사람의 절차(프리플라이트)에만 기대지 않는다.</li>
 *   <li>{@link #getMaxPerRun()} → 그래도 대상이 상한을 넘으면 아무것도 하지 않고 중단한다(부분 강등 금지).</li>
 * </ol>
 * 즉 실제로 강등이 일어나려면 {@code enabled=true} + {@code mode=ENFORCE} 두 개를 <b>모두</b> 켜야 한다.
 */
@Validated
@ConfigurationProperties(prefix = "hajacheck.plan.expiry")
public class PlanExpiryProperties {

    /**
     * 실행 모드 — 강등을 실제로 반영할지, 대상만 관찰할지.
     *
     * <p>{@code enabled} 와 역할이 다르다: {@code enabled=false} 는 배치 자체를 끄는 것(조회도 안 함)이고,
     * {@code DRY_RUN} 은 <b>배치는 돌되 쓰기만 하지 않는</b> 관찰 모드다. 운영 승격 순서는
     * enabled=true(DRY_RUN) → 로그로 대상 확인 → mode=ENFORCE 다.
     */
    public enum Mode {
        /** 대상 조회·집계·id 로그만 하고 강등하지 않는다(기본). */
        DRY_RUN,
        /** 실제로 FREE 강등을 반영한다. */
        ENFORCE
    }

    /**
     * 기동 스위치 — <b>기본 false(비활성)</b>. false면 스케줄러가 트리거돼도 대상 조회조차 하지 않고
     * 즉시 반환한다(클래스 javadoc의 소급 대량 강등 위험 참고).
     */
    private boolean enabled = false;

    /**
     * 실행 모드 — <b>기본 {@link Mode#DRY_RUN}</b>. {@code enabled=true} 로 켜도 이 값이 DRY_RUN 인 한
     * 단 한 건도 강등되지 않는다. "켜자마자 일괄 강등"이라는 최악의 사고를 한 단계 더 막는 장치다.
     */
    @NotNull
    private Mode mode = Mode.DRY_RUN;

    /**
     * 유예 기간 — 기본 {@code P0D}(만료 즉시 강등). 판정식은
     * {@code current_period_end < now - gracePeriod} 다.
     *
     * <p>0일이 기본인 이유: 빌링키(정기결제) 수단이 없어 만료 시점에 자동 청구를 할 수 없으므로,
     * 유예를 둬도 그 기간에 결제를 유도할 방법이 없다. 정책이 바뀔 수 있으니 설정으로만 빼 둔다
     * (#1145 §2 확정 결정 — 임의 변경 금지).
     *
     * <p>⚠️ <b>음수를 허용하지 않는다</b>({@link #isGracePeriodNotNegative()} 위반 시 기동 실패). 부호를
     * 잘못 넣으면({@code -P30D}) 기준시각이 <b>미래</b>가 되어 아직 유효한 유료 구독까지 대상이 된다.
     * 하필 이 값을 만지는 시점이 {@code enabled=true} 를 켜는 시점이라, 조용히 통과시키면 안 된다.
     */
    @NotNull
    private Duration gracePeriod = Duration.ZERO;

    /**
     * 강등 하한 컷오프 — 설정되면 {@code current_period_end >= notBefore} 인 구독만 대상이 된다.
     * 비워 두면(기본 {@code null}) 제한 없음.
     *
     * <p><b>이 값의 목적</b>: V27 백필이 만든 "실제 결제일이 아닌 추정 만료일" 구간을 <b>쿼리 단계에서</b>
     * 통째로 배제하는 것이다. 운영자가 프리플라이트로 확인한 시점(예: V27 배포 시각) 이후에 정상적으로
     * 만료된 구독만 강등하게 만들 수 있다. {@code max-per-run} 은 "대상이 많으면 멈춘다"는 사후 방어라
     * 대상이 소수일 때는 작동하지 않는 반면, 이 컷오프는 <b>건수와 무관하게</b> 과거 구간을 막는다.
     *
     * <p>⚠️ <b>{@code mode=ENFORCE} 에서는 필수다</b>({@link #isNotBeforeSetWhenEnforcing()} 위반 시 기동
     * 실패). "무설정 = 무제한"을 허용하면 {@code enabled=true} 와 {@code mode=ENFORCE} 를 <b>같은 배포에서
     * 함께</b> 넣는 것만으로 DRY_RUN 단계를 한 번도 거치지 않고 백필 추정치 전 구간이 첫 회차에 일괄
     * 강등된다 — 절차 문서로만 막는 것은 통제가 아니라 조언이다. 의도적으로 제한 없이 돌려야 하면
     * {@link #isNotBeforeUnbounded()} 를 <b>명시적으로</b> true 로 선언해야 한다.
     *
     * <p>ISO-8601 instant 로 설정한다(예: {@code 2026-08-01T00:00:00Z}).
     */
    private Instant notBefore;

    /**
     * 컷오프 없이(전 구간 대상) ENFORCE 를 돌리겠다는 <b>명시적 선언</b> — 기본 false.
     *
     * <p>{@link #getNotBefore()} 의 "무설정 = 무제한" 기본값을 금지하는 대신, 정말 전 구간을 강등해야 하는
     * 상황(예: 백필 구간을 이미 손으로 정리해 둔 뒤)을 위해 남겨 둔 탈출구다. 값을 넣는 행위 자체가
     * "이 결과를 알고 있다"는 기록이 되므로 실수로 미설정된 상태와 구분된다.
     *
     * <p>{@code notBefore} 와 <b>동시에</b> 설정할 수 없다({@link #isNotBeforeUnambiguous()}) — 둘 다 있으면
     * 어느 쪽이 의도인지 코드가 알 수 없다.
     */
    private boolean notBeforeUnbounded = false;

    /**
     * 강등 대상 행 잠금 대기 상한(ms) — 기본 3000. 0 이면 무제한 대기(권장하지 않음).
     *
     * <p>{@code @Scheduled} 기본 스레드 풀은 1개인데 이 앱에는 스케줄러가 여럿이다. 한 건의 잠금 대기가
     * 무한정 늘어지면 그 스레드가 묶여 <b>다른 배치까지 통째로 멈춘다</b>. 대기 상한을 걸면 획득 실패가
     * 해당 1건의 실패로 격리되고 다음 회차에 자연 재시도된다.
     *
     * <p>⚠️ 이 값은 JPA {@code jakarta.persistence.lock.timeout} 힌트로는 적용되지 않는다 —
     * {@code PostgreSQLDialect.supportsWait()} 가 <b>false</b> 라(hibernate-core 6.5.3 바이트코드로 확인)
     * 밀리초 단위 힌트가 조용히 무시되고 평범한 {@code for update} 로 나간다. 그래서 트랜잭션 로컬
     * {@code set_config('lock_timeout', …, true)} 로 적용한다
     * ({@code UserPlanRepository#applyLockTimeout}).
     */
    @Min(0)
    private int lockTimeoutMs = 3000;

    /**
     * 1회 실행당 강등 상한 — 기본 50. <b>초과하면 아무것도 하지 않고 중단</b>한다(부분 강등도 만들지
     * 않는다). 상한을 넘는 대량 강등은 정상 운영이 아니라 사고 신호({@code current_period_end} 백필
     * 오염·시각 판정 오류 등)이므로, "일부만 처리"보다 "전부 멈추고 사람을 부른다"가 안전하다.
     *
     * <p>⚠️ 이 값 <b>하나만으로는 소급 대량 강등을 막지 못한다</b> — 대상이 상한 이하이면 그대로 통과하기
     * 때문이다. 초기 서비스 규모에서는 "50건 이하"가 오히려 정상 시나리오라, 실질적인 통제는
     * {@link #getMode()} 와 {@link #getNotBefore()} 가 담당한다.
     */
    @Min(1)
    private int maxPerRun = 50;

    /**
     * 유예 기간 음수 금지(기동 실패 조건) — 자세한 이유는 {@link #getGracePeriod()} javadoc 참고.
     * 읽기 전용 파생 속성이라 설정 바인딩 대상이 아니다(setter 없음).
     */
    @AssertTrue(message = "hajacheck.plan.expiry.grace-period 는 음수일 수 없다 — "
            + "음수면 만료 기준시각이 미래가 되어 아직 유효한 유료 구독까지 강등 대상이 된다")
    public boolean isGracePeriodNotNegative() {
        return gracePeriod != null && !gracePeriod.isNegative();
    }

    /**
     * ENFORCE 로 올릴 때 컷오프 선언 필수(기동 실패 조건) — 자세한 이유는 {@link #getNotBefore()} javadoc.
     * 읽기 전용 파생 속성이라 설정 바인딩 대상이 아니다(setter 없음).
     */
    @AssertTrue(message = "hajacheck.plan.expiry.not-before 는 mode=ENFORCE 일 때 필수다 — "
            + "컷오프 없이 ENFORCE 로 올리면 V27 백필 추정치 구간이 첫 회차에 일괄 강등되고, "
            + "제품 안에는 되돌릴 경로가 없다(#988 무결제 상향 차단). 의도적으로 전 구간을 대상으로 "
            + "삼아야 하면 not-before-unbounded=true 를 명시할 것")
    public boolean isNotBeforeSetWhenEnforcing() {
        return mode != Mode.ENFORCE || notBefore != null || notBeforeUnbounded;
    }

    /** {@code not-before} 와 {@code not-before-unbounded} 를 동시에 선언할 수 없다(의도 모호). */
    @AssertTrue(message = "hajacheck.plan.expiry 의 not-before 와 not-before-unbounded 는 "
            + "동시에 설정할 수 없다 — 둘 다 있으면 어느 쪽이 의도인지 알 수 없다")
    public boolean isNotBeforeUnambiguous() {
        return !(notBefore != null && notBeforeUnbounded);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /** 실제 강등을 반영하는 상태인가 — {@code enabled} 와 {@code ENFORCE} 를 <b>모두</b> 만족해야 true. */
    public boolean isEnforcing() {
        return enabled && mode == Mode.ENFORCE;
    }

    public Duration getGracePeriod() {
        return gracePeriod;
    }

    public void setGracePeriod(Duration gracePeriod) {
        this.gracePeriod = gracePeriod;
    }

    public Instant getNotBefore() {
        return notBefore;
    }

    public void setNotBefore(Instant notBefore) {
        this.notBefore = notBefore;
    }

    public boolean isNotBeforeUnbounded() {
        return notBeforeUnbounded;
    }

    public void setNotBeforeUnbounded(boolean notBeforeUnbounded) {
        this.notBeforeUnbounded = notBeforeUnbounded;
    }

    public int getLockTimeoutMs() {
        return lockTimeoutMs;
    }

    public void setLockTimeoutMs(int lockTimeoutMs) {
        this.lockTimeoutMs = lockTimeoutMs;
    }

    public int getMaxPerRun() {
        return maxPerRun;
    }

    public void setMaxPerRun(int maxPerRun) {
        this.maxPerRun = maxPerRun;
    }
}
