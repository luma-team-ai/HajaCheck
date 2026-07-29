package com.hajacheck.membership.service;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.config.ScheduledPlanChangeProperties;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.repository.PlanRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>미결제 유예</b>(#1177 — 유료→유료 하향 C안 "유예 후 강등") 판정·해석의 <b>단일 진실 소스</b>.
 *
 * <h2>왜 이 상태가 존재하는가</h2>
 * {@code ENTERPRISE → STANDARD} 같은 유료→유료 하향은 적용 시점이 <b>무인 배치</b>라 그 순간 결제창을
 * 띄울 수 없다. 그렇다고 새 유료 주기를 그냥 열면 <b>어떤 경로로도 청구되지 않는 유료 한 달</b>이
 * 발급되어 결제 경로 우회가 된다(#1105 보안 리뷰 P1). C안은 그 사이를 "미결제 유예"로 메운다 —
 * 대상 요금제를 발급하되 <b>엔타이틀먼트는 FREE 로 낮추고</b>, 유예 안에 결제하면 정상 주기를 시작하고
 * 넘기면 FREE 로 강등한다. 결국 <b>무결제로 얻는 유료 혜택은 0</b> 이다.
 *
 * <h2>판정 = {@code user_plans.payment_pending_until IS NOT NULL}</h2>
 * 명시 컬럼(V33) 한 개다. 조인도 추가 조회도 없다.
 *
 * <p><b>파생 판정을 쓰지 않는 이유(1차 구현 회귀)</b>: 처음에는 스키마 변경을 피하려고 "유료 요금제 AND
 * {@code scheduled_plan_changes} APPLIED 이력 AND 연결된 PAID 결제 없음"으로 파생 판정했는데 네 가지가
 * 무너졌다 — ①조인 축이 {@code applied_at == current_period_start} 라는 암묵적 시각 일치에 의존했고,
 * ②{@code payments.user_plan_id} 는 <b>플랜 전이가 성공한 뒤에만</b> 채워지는데 유예 행은 그 분기를 타지
 * 않으므로 "PAID 없음"이 유예 행에 대해 항상 참인 <b>죽은 조건</b>이었으며(그래서 승인은 됐지만 전이가
 * 끝나지 않은 대사 대상(#1010)이 유예로 오판정돼 강등될 수 있었다), ③관리자 즉시 변경이 주기를 승계하면
 * {@code plan_id} 가 바뀌어 판정이 영구히 거짓이 됐고(청구 없는 유료 구독이 무기한 지속), ④엔타이틀먼트
 * 판정 hot path 가 {@code status='APPLIED'} 조회인데 기존 인덱스는 둘 다 PENDING 부분 인덱스라 seq scan
 * 이었다. 컬럼 하나로 넷이 동시에 사라진다.
 *
 * <h2>표식이 붙고 떨어지는 지점(전부 엔티티 메서드로만)</h2>
 * <ul>
 *   <li><b>붙는다</b> — 예약 실행(유료 대상): {@code UserPlan#startPaymentGracePeriod}</li>
 *   <li><b>승계된다</b> — 관리자 즉시 변경 → 다른 <b>유료</b> 요금제:
 *       {@code UserPlan#carryOverBillingPeriod(previous, true)}. 승계하지 않으면 갈아타기만으로 유예가
 *       세탁돼 청구 없는 유료 구독이 무기한 유지된다.</li>
 *   <li><b>떨어진다</b> — 결제 승인(정산·상향): {@code PlanTransitionService#transitionTo} 가 새 구독 행을
 *       발급하므로 표식 없는 상태로 시작한다. 즉시 변경 → FREE·유예 만료 강등도 대상이 무료라
 *       {@code carryOverBillingPeriod(previous, false)} 가 NULL 로 만든다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentGraceService {

    private final PlanRepository planRepository;
    private final ScheduledPlanChangeProperties properties;

    /**
     * 이 구독이 <b>미결제 유예</b> 상태인가 — 표식 컬럼의 존재 여부가 전부다.
     *
     * <p>"마감이 지났는가"를 함께 보지 <b>않는</b> 것은 의도적이다. 강등 배치는 매시라 마감 직후 최대
     * 1시간 동안 표식이 남은 미결제 구독이 존재하는데, 그 구간에 유료 엔타이틀먼트를 돌려주면 이 기능이
     * 막으려던 무결제 유료 사용이 정확히 그만큼 열린다. 만료 여부는 강등 배치가
     * {@code payment_pending_until < now} 로 따로 판정한다({@code UserPlan#isPaymentPending} javadoc).
     */
    public boolean isPaymentPending(UserPlan userPlan) {
        return userPlan != null && userPlan.isPaymentPending();
    }

    /**
     * 이 구독에 <b>실제로 적용할</b> 요금제 — 유예 중이면 FREE, 아니면 구독 요금제 그대로.
     *
     * <p><b>왜 FREE 인가</b>(#1177 결정): 유예 중에 대상 요금제의 혜택을 주면 예약을 반복하는 것만으로
     * 유예기간만큼 상위 요금제를 무료로 쓸 수 있다 — #1105 보안 P1(무결제 유료 발급)과 같은 부류의
     * 우회로다. 좌석 정지 기준도 같다({@code ScheduledPlanChangeWriter} 가 유예 진입 시점에 FREE 기준으로
     * 초과 좌석을 정리한다) — 한도와 정지 기준이 갈라지면 "정지는 안 됐는데 아무것도 못 하는" 인원이 생긴다.
     *
     * <p><b>⚠️ 한도 3종만의 문제가 아니다</b>(리뷰 P1): {@link Plan} 의 엔타이틀먼트는
     * {@code maxFacilities}·{@code maxMonthlyAnalyses}·{@code maxSeats} 외에 <b>{@code hasCounselorAccess}
     * ·{@code hasAiAddon}</b> 가 더 있고, 그 둘은 {@code QuotaService} 를 거치지 않고
     * {@code CounselTicketService}·{@code NlSearchService} 가 각자 원본 요금제를 직접 읽어 판정한다.
     * 그래서 <b>요금제 객체를 통째로 바꿔치기</b>하는 이 메서드를 그 두 경로도 반드시 경유해야 한다 —
     * 한 곳이라도 원본 {@code plan} 을 그대로 읽으면 유예 구독이 상담사 연결·AI 부가기능을 무상으로 쓴다.
     * (1차 구현이 정확히 그 상태였고, "무결제로 얻는 유료 혜택은 0"이라는 이 클래스의 주장이 거짓이었다.)
     *
     * <p>⚠️ 트레이드오프는 의도된 것이다: 유예 진입 시점에 좌석이 FREE 기준으로 정리되므로 체감은 즉시
     * 강등에 가깝다. 그럼에도 이 방향을 택한 건 무결제 유료 혜택 구멍을 만들지 않기 위해서다.
     */
    public Plan resolveEffectivePlan(UserPlan userPlan, Plan plan) {
        return isPaymentPending(userPlan) ? freePlan() : plan;
    }

    /**
     * 이 구독의 <b>결제 마감 시각</b>(유예 마감). 유예 중이 아니면 {@code null}.
     *
     * <p>발급 시점에 {@code current_period_start + payment-grace-days}(KST)로 쓰인 값이며
     * ({@code UserPlan#startPaymentGracePeriod}), 강등 배치가 실제로 강제하는 기준도 같은 값이다. 설정값으로
     * 매번 다시 계산하지 않는 이유: 운영 중 {@code payment-grace-days} 를 바꾸면 화면·알림이 말하는 마감과
     * 배치가 강제하는 마감이 갈라지고, 진행 중인 유예가 소급해 늘거나 줄어 보인다.
     */
    public Instant resolveGraceDeadline(UserPlan userPlan) {
        return userPlan == null ? null : userPlan.getPaymentPendingUntil();
    }

    /** 유예 발급 시 열어 줄 일수(설정값) — {@code UserPlan#startPaymentGracePeriod} 호출부 전용. */
    public int graceDays() {
        return properties.getPaymentGraceDays();
    }

    /**
     * 유료 대상 하향 예약을 받아도 되는가(#1177 킬 스위치, <b>기본 false</b>).
     *
     * <p>백엔드는 열렸지만 프론트에는 아직 유료 대상 예약 UI(결제 마감 안내·유예 배너·결제 유도)가 없다.
     * 그 상태로 API 만 열면 API 로 직접 진입한 사용자가 <b>안내 없이</b> 좌석 정지와 FREE 강등을 맞는다 —
     * 사람 없는 배치가 권한을 내리는 기능이라 "모르는 사이 당하는" 상황을 만들면 안 된다. 프론트 연동이
     * 끝나면 배포 없이 설정으로 뒤집는다.
     */
    public boolean isPaidTargetScheduleEnabled() {
        return properties.isPaidTargetEnabled();
    }

    /**
     * 유예 중 적용할 FREE 요금제. 시드가 없으면 설정 오류이므로 fail-fast 한다
     * ({@code PlanProvisioningService}·{@code PlanExpiryWriter} 와 같은 판단).
     */
    public Plan freePlan() {
        return planRepository.findByName(PlanName.FREE)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
    }
}
