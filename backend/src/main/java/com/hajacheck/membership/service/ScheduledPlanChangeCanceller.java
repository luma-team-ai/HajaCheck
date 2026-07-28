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
 * <p>⚠️ <b>잠금 순서</b>: 이 호출은 {@code scheduled_plan_changes} 행 잠금을 잡는다. 사용자 요청 경로
 * (즉시 변경)와 결제 전이는 이 호출이 <b>첫 행 잠금</b>이라 예약 실행 배치
 * ({@code ScheduledPlanChangeWriter}: 예약 → 구독 → 사용자)와 순서가 일치한다. 만료 강등
 * ({@code PlanExpiryWriter})만은 대상 구독을 먼저 잠근 뒤에야 강등 여부를 확정할 수 있어(주기가 갱신된
 * 구독의 예약을 잘못 취소하면 안 된다) 순서가 반대다 — 두 배치가 동시에 같은 구독을 처리하면 이론상
 * 교착이 가능하지만, ①두 배치는 같은 {@code @Scheduled} 단일 스레드에서 직렬 실행되고 ②교착이 나더라도
 * PostgreSQL 교착 감지가 한쪽을 중단시키며 그 예외({@code DeadlockLoserDataAccessException} 는
 * {@code PessimisticLockingFailureException} 의 하위형)를 <b>두 스케줄러 모두 재시도 가능한 스킵으로
 * 분류</b>해 다음 회차에 자연 복구된다. 사용자 요청이 이 순환에 끼어들 수 없다는 점이 핵심이다.
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
}
