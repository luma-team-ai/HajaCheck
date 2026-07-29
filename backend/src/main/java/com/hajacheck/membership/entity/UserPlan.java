package com.hajacheck.membership.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.ZoneId;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 개인/회사 구독 — DDL user_plans 테이블 대응. auth 도메인의 사용자·회사는 식별자로 유지하고,
 * 같은 membership 도메인의 Plan은 지연 로딩 관계를 함께 제공한다.
 *
 * <p>owner XOR(userId 또는 companyId 중 정확히 하나)는 DB 제약({@code ck_user_plans_owner_xor})이 보장하며,
 * 이 엔티티는 팩토리 메서드 {@link #forUser}/{@link #forCompany} 로만 생성해 애플리케이션 레벨에서도 XOR 을 강제한다.
 *
 * <p>created_at/updated_at 컬럼이 DDL 에 없어 BaseTimeEntity 를 상속하지 않는다(startedAt 이 생성시각 역할).
 *
 * <p><b>결제 주기(#1104 / HAJA-525)</b>: {@code currentPeriodStart}/{@code currentPeriodEnd} 는
 * {@code nextBillingDate}(과거 {@code startedAt + 1개월} 파생 계산) 를 실체화한 컬럼이다. FREE 는
 * {@code currentPeriodEnd == null}(무기한)이고, 유료 플랜은 결제 승인 시 {@link #startNewBillingPeriod}
 * 로 리셋되거나 관리자 플랜 변경(무결제) 시 {@link #carryOverBillingPeriod} 로 이전 값이 승계된다 —
 * 어느 쪽이든 서비스가 필드를 직접 대입하지 않고 이 엔티티 메서드로만 전이한다(Setter 금지 컨벤션).
 */
@Entity
@Getter
@Table(name = "user_plans", indexes = {
        @Index(name = "idx_user_plans_user", columnList = "user_id"),
        @Index(name = "idx_user_plans_company", columnList = "company_id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPlan {

    // 결제 주기 1개월 계산 존 — MyPlanResponse/PlatformAdminPlanQuotaService 등 기존 파생 계산이 쓰던
    // 존과 동일하게 KST 로 고정한다(서버 JVM 기본존도 Dockerfile 에서 Asia/Seoul 로 고정돼 있어 정합).
    private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Seoul");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", insertable = false, updatable = false)
    private Plan plan;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "user_plan_status_type", nullable = false)
    private UserPlanStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "current_period_start")
    private Instant currentPeriodStart;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Builder(access = AccessLevel.PRIVATE)
    private UserPlan(Long userId, Long companyId, Long planId, UserPlanStatus status, Instant startedAt) {
        this.userId = userId;
        this.companyId = companyId;
        this.planId = planId;
        this.status = status == null ? UserPlanStatus.ACTIVE : status;
        this.startedAt = startedAt == null ? Instant.now() : startedAt;
    }

    /** 개인 구독 생성 팩토리 — companyId 는 null(owner XOR). */
    public static UserPlan forUser(Long userId, Long planId) {
        return UserPlan.builder()
                .userId(userId)
                .planId(planId)
                .status(UserPlanStatus.ACTIVE)
                .build();
    }

    /** 회사 구독 생성 팩토리 — userId 는 null(owner XOR). */
    public static UserPlan forCompany(Long companyId, Long planId) {
        return UserPlan.builder()
                .companyId(companyId)
                .planId(planId)
                .status(UserPlanStatus.ACTIVE)
                .build();
    }

    /**
     * 업그레이드 문의(PG 실결제 대체) — status를 UPGRADE_REQUESTED 로 전이한다.
     * 멱등: 이미 UPGRADE_REQUESTED 면 no-op(계약: "이미 요청 상태면 그대로 200").
     */
    public void requestUpgrade() {
        if (this.status == UserPlanStatus.UPGRADE_REQUESTED) {
            return;
        }
        this.status = UserPlanStatus.UPGRADE_REQUESTED;
    }

    /**
     * 구독 만료 전이(플랜 변경 시 사용) — table_design.md §user_plans "구독 업그레이드/갱신은 단일 트랜잭션 내에서
     * 기존 ACTIVE를 EXPIRED로 내리고 신규를 ACTIVE로 올리는 순서로 처리한다"에 따라, 관리자 플랜 변경(#507)은
     * 기존 활성 구독을 이 메서드로 EXPIRED 로 내린 뒤 신규 ACTIVE 행을 발급한다. endedAt 을 종료 시각으로 기록해
     * user_plans 행 자체가 "언제 어느 플랜에서 어느 플랜으로 바뀌었는지"의 변경 이력이 된다(부분 UQ
     * uq_user_plans_active_company 와 정합 — ACTIVE 는 항상 최대 1건).
     */
    public void expire() {
        this.status = UserPlanStatus.EXPIRED;
        this.endedAt = Instant.now();
    }

    public boolean isOwnedByUser(Long candidateUserId) {
        return this.userId != null && this.userId.equals(candidateUserId);
    }

    /**
     * 새 결제 주기 개시(#1104) — 결제 승인 전이({@code PlanTransitionService#transitionTo})에서 신규
     * 발급된 구독에 호출한다. 새로 돈을 냈으므로 이전 주기를 승계하지 않고 {@code periodStart} 부터
     * 1개월을 새로 연다({@link #carryOverBillingPeriod} 와 반대 규칙 — 둘을 혼동하면 "결제일이 밀리는"
     * 원래 버그(파생 계산 시절 startedAt 리셋)가 재발한다).
     */
    public void startNewBillingPeriod(Instant periodStart) {
        this.currentPeriodStart = periodStart;
        this.currentPeriodEnd = periodStart.atZone(BILLING_ZONE).plusMonths(1).toInstant();
    }

    /**
     * 결제 주기 승계(#1104) — 관리자 콘솔의 무결제 플랜 변경({@code AdminPlanService#changePlan})에서
     * 신규 발급된 구독에 호출한다. 결제가 없었으므로 {@code previous} 의 현재 결제 주기를 그대로
     * 물려받는다 — 여기서 리셋하면 관리자가 플랜만 바꿔도 결제일이 밀리는 버그가 그대로 남는다.
     *
     * <p><b>단, 대상 플랜이 무료면 {@code currentPeriodEnd} 는 승계하지 않고 NULL(무기한)로 둔다</b>
     * (리뷰 P1, #1104). 유료→FREE 하향은 {@code AdminPlanService#requireNotUpgrade} 가 막는 "상향"이
     * 아니라 정상 허용 경로인데, 여기서 무조건 승계하면 FREE 구독에 과거 유료 주기의 만료일이 그대로
     * 남아 (1) 마이페이지에 무료인데 다음 결제일이 표시되고 (2) 그 날짜가 지나면
     * {@code PlatformAdminPlanQuotaService} 가 EXPIRED 로 오판한다 — 이 PR 이 고치겠다고 선언한 바로 그
     * 버그(FREE 는 {@code currentPeriodEnd == null})가 승계 경로로 재발한다. {@code currentPeriodStart}
     * 는 무료여도 승계한다(구독 개시 시점 자체는 여전히 유효한 정보라 굳이 지울 이유가 없다).
     *
     * @param targetIsPaid 신규 발급되는 대상 플랜이 유료인지 — 호출부가 {@code plans.price_monthly}
     *                     기준으로 판정해 넘긴다(티어 이름이 아니라 가격이 유일한 기준 —
     *                     {@code AdminPlanService#requireNotUpgrade} javadoc과 동일 근거: 가격은
     *                     플랫폼 관리자가 바꿀 수 있어 이름 순서와 어긋날 수 있다).
     */
    public void carryOverBillingPeriod(UserPlan previous, boolean targetIsPaid) {
        this.currentPeriodStart = previous.currentPeriodStart;
        this.currentPeriodEnd = targetIsPaid ? previous.currentPeriodEnd : null;
    }

    /**
     * 미결제 유예 주기 개시(#1177 — 유료→유료 하향 C안 "유예 후 강등") — 예약 하향 실행 배치
     * ({@code ScheduledPlanChangeWriter})가 <b>유료 대상</b>으로 신규 발급한 구독에 호출한다.
     *
     * <p>적용 시점이 무인 배치라 그 순간 결제를 받을 수 없다. 그렇다고 {@link #startNewBillingPeriod} 로
     * 새 유료 1개월을 열면 <b>어떤 경로로도 청구되지 않는 유료 한 달</b>이 발급된다(#1105 보안 리뷰 P1 =
     * 결제 경로 우회). 그래서 여기서는 <b>{@code graceDays} 만큼만</b> 주기를 연다 — 그 안에 결제하면
     * 결제 경로가 정상 1개월 주기로 새 구독을 발급하고, 넘기면 예약 스케줄러 2단계가 FREE 로 강등한다.
     *
     * <p><b>유예 중 한도는 FREE 다</b>({@code QuotaService} 가
     * {@code PaymentGraceService#resolveEffectivePlan} 으로 낮춘다) — 유료 한도를 주면 예약을 반복해
     * 유예기간만큼 무료로 쓰는 구멍이 생긴다(#1177 결정표). 즉 이 기간에 무결제로 얻는 유료 혜택은 없고,
     * 유료인 것은 티어 이름과 "결제하면 그 요금제가 된다"는 예약뿐이다.
     *
     * <p><b>⚠️ {@code periodStart} 는 예약 실행 배치의 기준 시각({@code now})과 정확히 같은 값이어야
     * 한다</b> — 유예 여부는 컬럼이 아니라 <b>파생 판정</b>이고({@code PaymentGraceService}) 그 판정이
     * "이 구독이 예약 실행으로 생겼다"는 증거로 {@code scheduled_plan_changes.applied_at == user_plans
     * .current_period_start} 를 쓰기 때문이다. 같은 트랜잭션에서 두 값을 같은 {@code now} 로 쓰므로
     * 성립하며, 다른 값을 넘기면 발급된 유예 구독을 아무도 유예로 인식하지 못한다(= 청구되지 않는 유료
     * 구독이 영구히 남는다).
     *
     * <p>일수 가산은 {@link #BILLING_ZONE}(KST) 기준이다 — {@link #startNewBillingPeriod} 의 월 가산과
     * 같은 존을 쓰지 않으면 자정 전후(KST 00~09시 = UTC 전날)에 하루가 밀린다.
     *
     * @param periodStart 유예 시작 시각(= 예약 적용 기준 시각 {@code now})
     * @param graceDays   유예 일수({@code hajacheck.plan.scheduled-change.payment-grace-days}, 기본 7)
     */
    public void startPaymentGracePeriod(Instant periodStart, int graceDays) {
        this.currentPeriodStart = periodStart;
        this.currentPeriodEnd = periodStart.atZone(BILLING_ZONE).plusDays(graceDays).toInstant();
    }
}
