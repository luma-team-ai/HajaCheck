package com.hajacheck.membership.service;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.dto.DowngradeOverflow;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 주기가 만료된 구독 1건을 FREE 로 강등하는 <b>DB 트랜잭션 전담</b> 빈(#1145 / HAJA-549).
 *
 * <p>{@code PlanExpiryScheduler}(조회·순회 담당)와 별도 빈으로 둔다 — self-invocation 으로
 * {@code @Transactional} 프록시가 풀리는 것을 막기 위해서다({@code PaymentWriter}·
 * {@code PendingBusinessReverifyWriter} 와 동일한 이유).
 *
 * <p><b>건별 독립 트랜잭션({@code REQUIRES_NEW})</b>: 한 구독의 실패(경합·데이터 이상)가 같은 회차의
 * 나머지 구독 처리를 막으면 안 된다. 스케줄러 메서드에는 {@code @Transactional} 을 붙이지 않는다
 * ({@code InspectionDueNotificationScheduler} 와 동일 원칙).
 *
 * <p><b>⚠️ 전이 로직을 새로 짜지 않는다(#1145 §3-2)</b>. 하향은 이미 관리자 콘솔
 * ({@code AdminPlanService#changePlan})이 쓰는 경로가 있고, 두 경로가 갈라지면 정지 규칙이 어긋난다.
 * 그래서 순서·구성 요소를 그대로 재사용한다:
 * <ol>
 *   <li>{@link PlanDowngradeService#preview}(부작용 없음) — <b>반드시 쓰기 이전에 정확히 한 번</b>
 *       (그 클래스 javadoc 의 F-7 원칙). 결과를 그대로 {@link PlanDowngradeService#applyOverflow} 에
 *       넘겨 미리보기와 실제 적용이 어긋나지 않게 한다.</li>
 *   <li>{@link UserPlan#expire()} → flush → FREE ACTIVE 신규 발급. 부분 UQ
 *       ({@code uq_user_plans_active_company}/{@code uq_user_plans_active_user}: ACTIVE 최대 1건)를
 *       만족시키려 만료 UPDATE 를 먼저 flush 한다.</li>
 *   <li>{@link UserPlan#carryOverBillingPeriod}{@code (previous, false)} — 결제가 없었으므로 주기
 *       시작은 승계하고, 대상이 무료이므로 {@code currentPeriodEnd} 는 <b>NULL(무기한)</b> 로 둔다
 *       (#1104 규칙). 여기서 주기를 리셋하면(=startNewBillingPeriod) FREE 구독이 한 달 뒤 다시 이
 *       배치의 대상이 되는 무한 강등 루프가 된다.</li>
 *   <li>{@link PlanTransitionService#carryOverUsage} — 당월 사용량 이월(#851). 이월하지 않으면 강등이
 *       곧 월 분석 한도 리셋이 되어 한도 강제(#843)가 무력화된다.</li>
 *   <li>{@link PlanDowngradeService#applyOverflow} — 초과 좌석 SUSPENDED. 같은 트랜잭션에서 처리한다
 *       (플랜만 내려가고 정지가 안 되면 한도가 조용히 무력화된다).</li>
 * </ol>
 *
 * <p><b>멱등(#1145 §3-3)</b>: 강등이 끝난 구독은 EXPIRED 가 되고 신규 FREE 행은
 * {@code current_period_end = NULL} 이라, 다음 회차의 대상 조회 조건
 * ({@code ACTIVE AND current_period_end < threshold})에 더 이상 걸리지 않는다. 게다가 이 메서드는
 * 트랜잭션 안에서 대상 조건을 <b>다시 확인</b>하므로(아래 재검증), 스케줄러가 대상 id 를 읽은 뒤
 * 처리하기 전에 다른 경로(결제 승인·관리자 변경)가 먼저 전이시킨 경우에도 이중 강등하지 않는다.
 * 부분 UQ 위반({@link org.springframework.dao.DataIntegrityViolationException})은 여기서 잡지 않고
 * 그대로 던져 호출부(스케줄러)가 "이미 다른 경로가 활성 구독을 만듦"으로 스킵 처리하게 한다 —
 * 다음 회차에 자연 재시도된다.
 *
 * <p><b>알림은 여기서 발행하지 않는다</b> — 강등이 실제로 커밋된 뒤에 나가야 하므로 호출부가
 * {@link PlanExpiryResult} 를 받아 트랜잭션 밖에서 발행한다(그 record javadoc 참고).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanExpiryWriter {

    private final CompanyRepository companyRepository;
    private final PlanRepository planRepository;
    private final UserPlanRepository userPlanRepository;
    private final PlanTransitionService planTransitionService;
    private final PlanDowngradeService planDowngradeService;

    /**
     * 결제 주기가 만료된 구독 1건을 FREE 로 강등한다.
     *
     * @param userPlanId 강등 대상 구독 id(스케줄러가 조회한 것)
     * @param threshold  만료 판정 기준 시각({@code now - gracePeriod}) — 조회 시점과 <b>같은 값</b>을
     *                   넘겨야 재검증이 조회 조건과 일치한다
     * @return 강등 결과(스킵이면 {@link PlanExpiryResult#downgraded()} 가 false)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PlanExpiryResult expireToFreePlan(Long userPlanId, Instant threshold) {
        UserPlan current = userPlanRepository.findById(userPlanId).orElse(null);
        if (current == null) {
            // 조회~처리 사이에 사라진 행. 이론적으로 발생하지 않지만 배치를 죽이지 않는다.
            return PlanExpiryResult.skipped("구독 행 없음");
        }
        // ⚠️ 대상 조건 재검증 — 스케줄러의 조회는 잠금 없는 스냅샷이라, id 를 읽은 뒤 처리 전에 다른
        // 경로(결제 승인·관리자 플랜 변경)가 먼저 전이시켰을 수 있다. 그 상태에서 강등하면 방금 결제한
        // 유료 구독을 곧바로 내리게 된다.
        if (current.getStatus() != UserPlanStatus.ACTIVE) {
            return PlanExpiryResult.skipped("이미 ACTIVE 아님(status=" + current.getStatus() + ")");
        }
        Instant periodEnd = current.getCurrentPeriodEnd();
        if (periodEnd == null || !periodEnd.isBefore(threshold)) {
            return PlanExpiryResult.skipped("결제 주기가 갱신됨");
        }

        Long companyId = current.getCompanyId();
        Long userId = current.getUserId();
        Plan freePlan = planRepository.findByName(PlanName.FREE)
                // PlanProvisioningService 와 같은 조회·같은 fail-fast(시드 누락은 설정 오류다).
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
        Plan currentPlan = planRepository.findById(current.getPlanId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));

        // 알림 수신자는 쓰기 이전에 확정한다 — 회사 행을 못 찾으면 좌석 정지 계산(resolveSeatsToSuspend)도
        // 어차피 PLAN_FORBIDDEN 으로 실패하므로, 부분 상태를 남기지 않도록 여기서 먼저 끊는다.
        Long recipientUserId = companyId != null ? resolveCompanyOwnerUserId(companyId) : userId;

        // ⚠️ preview 는 쓰기 이전에 정확히 한 번(PlanDowngradeService F-7) — 결과를 그대로 applyOverflow 에
        // 재사용한다. 개인 구독(companyId=null)은 좌석·회사 시설물 개념이 없어 계산 자체를 건너뛴다.
        DowngradeOverflow overflow = companyId != null
                ? planDowngradeService.preview(companyId, currentPlan, freePlan)
                : null;

        current.expire();
        userPlanRepository.saveAndFlush(current);

        UserPlan renewed = companyId != null
                ? UserPlan.forCompany(companyId, freePlan.getId())
                : UserPlan.forUser(userId, freePlan.getId());
        // 결제가 아니라 만료에 의한 전이다 — 주기 시작은 승계하고 종료는 NULL(무기한). targetIsPaid=false
        // 는 대상이 FREE 라서 고정이다(가격 기준 판정과 결과가 같다).
        renewed.carryOverBillingPeriod(current, false);
        UserPlan saved = userPlanRepository.saveAndFlush(renewed);

        planTransitionService.carryOverUsage(current.getId(), saved.getId());

        int suspendedSeatCount = 0;
        if (overflow != null) {
            planDowngradeService.applyOverflow(companyId, freePlan, overflow);
            suspendedSeatCount = overflow.seatOverflowCount();
        }

        // 운영 로그(#1145 §5) — 누가(companyId/userId) 어느 플랜에서 언제 내려갔는지.
        log.info("구독 만료 FREE 강등 — companyId={} userId={} previousPlan={} periodEnd={} "
                        + "expiredAt={} suspendedSeats={}",
                companyId, userId, currentPlan.getName(), periodEnd, Instant.now(), suspendedSeatCount);

        return PlanExpiryResult.downgraded(
                recipientUserId, currentPlan.getName(), companyId, userId, periodEnd, suspendedSeatCount);
    }

    private Long resolveCompanyOwnerUserId(Long companyId) {
        return companyRepository.findById(companyId)
                .map(Company::getOwnerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
    }
}
