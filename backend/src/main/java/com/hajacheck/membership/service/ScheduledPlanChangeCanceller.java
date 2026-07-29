package com.hajacheck.membership.service;

import com.hajacheck.membership.repository.ScheduledPlanChangeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>구독이 전이되면 그 구독에 걸린 하향 예약은 무효</b>라는 단일 규칙을 담는 빈(#1105 / HAJA-526,
 * 리뷰 P2-3).
 *
 * <p><b>왜 별도 빈인가</b>: 이 규칙을 지켜야 하는 전이 경로가 셋이고 서로 다른 패키지에 있다.
 * <ol>
 *   <li>{@code AdminPlanService#changePlan} — 관리자 콘솔 즉시 변경</li>
 *   <li>{@link PlanTransitionService#transitionTo} — 결제 승인 전이(상향)</li>
 *   <li>{@link PlanExpiryWriter#expireToFreePlan} — 결제 주기 만료 FREE 강등(#1145)</li>
 * </ol>
 * 한 곳이라도 빠지면 예약 행이 <b>옛 {@code user_plan_id} 에 매달린 채 PENDING 으로</b> 남는다. 조회
 * ({@code AdminPlanService#findPendingScheduledChange})와 취소({@code AdminPlanService
 * #cancelScheduledChange})가 모두 <b>현재 구독 id 기준</b>이라, 그 예약은 화면에서 사라지면서도 owner 가
 * 취소할 방법이 없는 유령 예약이 된다(취소 시도는 404). 실제로 만료 강등 경로에서 이 누락이 발견됐다.
 * 그래서 규칙과 그 근거를 한 곳에 모으고, <b>새 전이 경로가 붙으면 여기를 호출하도록</b> 강제한다.
 *
 * <p><b>호출 위치</b>: 각 경로의 {@code current.expire()} <b>직전</b>이다. 그 자리가 "이 구독이 더 이상
 * 유효하지 않게 되는 순간"이고, 같은 트랜잭션 안이라 전이가 롤백되면 취소도 함께 되돌아간다.
 *
 * <p><b>⚠️ 잠금 순서 — 이 도메인의 전이 경로는 모두 {@code scheduled_plan_changes} → {@code user_plans}
 * → {@code users} 순으로만 잠근다.</b> 이 빈이 첫 번째 축의 잠금을 잡으므로, 새 전이 경로를 붙일 때도
 * <b>구독 행을 잠그기 전에</b> 여기를 먼저 통과시켜야 한다. 현재 네 경로가 전부 이 순서다:
 * <ul>
 *   <li>{@code AdminPlanService#changePlan}(사용자 요청) — {@link #cancelOnTransition} → {@code expire()}</li>
 *   <li>{@link PlanTransitionService#transitionTo}(결제 전이) — {@link #cancelOnTransition} → {@code expire()}</li>
 *   <li>{@code ScheduledPlanChangeWriter}(예약 실행 배치) — 예약 행 잠금 → 구독 행 잠금</li>
 *   <li>{@link PlanExpiryWriter}(만료 강등 배치) — {@link #lockPendingForTransition} → 구독 행 잠금 →
 *       (재검증 통과 후) {@link #cancelOnTransition}</li>
 * </ul>
 *
 * <p><b>만료 강등만 두 단계로 나눈 이유</b>(2차 검증 P2): 그 경로는 <b>구독 행을 잠그고 재검증한 뒤에야</b>
 * 강등 여부가 확정된다 — 주기가 갱신된 구독의 예약을 잘못 취소하면 안 되므로 취소 호출 자체를 앞으로
 * 옮길 수 없다. 그렇다고 취소를 뒤에 두면 잠금 순서가 <b>구독 → 예약</b>으로 뒤집혀, 같은 구독을 건드리는
 * <b>사용자 요청</b>(즉시 변경·결제 승인)과 ABBA 교착이 생긴다. {@code lock_timeout}(3초)은 PostgreSQL
 * 기본 {@code deadlock_timeout}(1초)보다 길어 방어가 되지 않고, 교착 희생자가 사용자 요청 쪽이 되면
 * 500 응답이 나간다(배치끼리의 교착과 달리 재시도로 흡수되지 않는다).
 *
 * <p>그래서 <b>잠금 획득과 취소 결정을 분리</b>했다: {@link #lockPendingForTransition} 이 구독 행보다
 * <b>먼저</b> 예약 행 잠금만 잡아 순서를 맞추고, 실제 취소 여부는 재검증 뒤 {@link #cancelOnTransition}
 * 이 정한다(그때는 이미 잠금을 쥐고 있어 추가 대기가 없다). 잠금을 미리 잡는 건 무해하다 — 스킵으로
 * 끝나도 트랜잭션 종료와 함께 풀리고, 대기 예약이 없는 대부분의 경우엔 잠글 행조차 없다.
 *
 * <p>대안과 기각 사유: ①스킵 경로를 예외로 바꿔 취소까지 롤백시키기 → 정상적인 스킵이 실패로 집계돼
 * {@code PlanExpiryScheduler} 회차 요약 WARN 이 일상적으로 켜지고 진짜 실패 신호가 희석된다.
 * ②{@code NOWAIT}·짧은 {@code lock_timeout} 으로 배치가 먼저 포기하게 하기 → 교착 자체를 없애지 못하고
 * 누가 희생자가 되는지는 여전히 타이밍에 달린다. ③#1162(만료 대상 조회에서 PENDING 예약 배제)에 위임 →
 * 별도 PR이라 이 PR 범위에서는 노출이 그대로 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledPlanChangeCanceller {

    private final ScheduledPlanChangeRepository scheduledPlanChangeRepository;

    /**
     * 이 구독에 걸린 대기 예약을 무효화한다 — <b>반드시 전이 트랜잭션 안에서</b>({@code MANDATORY}).
     * 별도 트랜잭션으로 미루면 전이가 롤백돼도 취소만 남아 예약이 사라진다.
     *
     * <p>대기 예약이 없으면 no-op(0행)이다 — 대부분의 전이에는 예약이 걸려 있지 않다.
     *
     * @param reason {@code failure_reason} 에 남길 사유. 사람이 원인을 특정하기 위한 값이라 개인정보를
     *               담지 않는다(id·상태·경로명만).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelOnTransition(Long userPlanId, String reason) {
        if (userPlanId == null) {
            return;
        }
        int canceled = scheduledPlanChangeRepository.cancelPendingByUserPlanId(userPlanId, reason);
        if (canceled > 0) {
            log.info("구독 전이로 하향 예약 무효화 — userPlanId={} canceled={} reason={}",
                    userPlanId, canceled, reason);
        }
    }

    /**
     * 이 구독의 대기 예약에 <b>행 잠금만</b> 잡아 둔다 — 취소하지 않는다.
     *
     * <p><b>취소 여부를 나중에 결정해야 하는 경로 전용</b>이다({@link PlanExpiryWriter}). 구독 행을
     * 잠그기 <b>직전</b>에 호출해 잠금 순서를 {@code scheduled_plan_changes} → {@code user_plans} 로
     * 맞추는 것이 유일한 목적이며, 그렇게 하지 않으면 사용자 요청과 ABBA 교착이 생긴다(자세한 근거는
     * 클래스 javadoc 의 <b>잠금 순서</b> 절). 실제 취소는 재검증을 통과한 뒤
     * {@link #cancelOnTransition} 이 수행한다 — 그때는 이 잠금을 이미 쥐고 있어 추가 대기가 없다.
     *
     * <p>취소하지 않고 끝나도(스킵) 잠금은 트랜잭션 종료와 함께 풀리므로 부작용이 없다. 대기 예약이
     * 없으면 잠글 행 자체가 없어 비용도 없다.
     *
     * <p>⚠️ 반환값을 쓰지 않는다 — 여기서 읽은 스냅샷으로 판정하면 안 되기 때문이다. 판정 근거는 항상
     * {@code cancelPendingByUserPlanId} 의 <b>조건부 UPDATE 갱신 행 수</b>다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPendingForTransition(Long userPlanId) {
        if (userPlanId == null) {
            return;
        }
        scheduledPlanChangeRepository.lockPendingByUserPlanId(userPlanId);
    }
}
