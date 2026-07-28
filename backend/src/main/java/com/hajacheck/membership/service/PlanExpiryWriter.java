package com.hajacheck.membership.service;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.config.PlanExpiryProperties;
import com.hajacheck.membership.dto.DowngradeOverflow;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
 *   <li>{@link UserPlan#carryOverBillingPeriod}{@code (previous, isPaid(freePlan))} — 결제가 없었으므로
 *       주기 시작은 승계하고, 대상이 무료이므로 {@code currentPeriodEnd} 는 <b>NULL(무기한)</b> 이 된다
 *       (#1104 규칙). 유료 여부 판정 근거는 {@code AdminPlanService} 와 같은 {@code plans.price_monthly}
 *       다. 여기서 주기가 남으면 FREE 구독이 다음 회차에 다시 이 배치의 대상이 되는 <b>무한 강등
 *       루프</b>가 되므로, 값이 아니라 <b>결과</b>를 사후 단정으로 막는다(아래 §안전 불변식).</li>
 *   <li>{@link PlanTransitionService#carryOverUsage} — 당월 사용량 이월(#851). 이월하지 않으면 강등이
 *       곧 월 분석 한도 리셋이 되어 한도 강제(#843)가 무력화된다.</li>
 *   <li>{@link PlanDowngradeService#applyOverflow} — 초과 좌석 SUSPENDED. 같은 트랜잭션에서 처리한다
 *       (플랜만 내려가고 정지가 안 되면 한도가 조용히 무력화된다).</li>
 * </ol>
 *
 * <p><b>⚠️ 안전 불변식 — 강등 결과의 {@code currentPeriodEnd} 는 반드시 NULL(리뷰 NEW-1)</b>: 유료 여부를
 * 가격으로 판정하게 되면서, <b>FREE 요금제에 양수 가격이 설정되면</b>({@code PlatformAdminPlanPolicy
 * UpdateRequest} 의 가격 제약이 {@code @DecimalMin("0")} 이라 가능하고 서비스에도 FREE 전용 가드가 없다)
 * 과거 만료일이 그대로 승계돼 새 FREE 행이 <b>즉시 다음 회차 대상</b>이 된다 — 매일 밤 강등·알림·행 증식이
 * 무한 반복된다. 그래서 전이 직후 결과를 단정하고, 어긋나면 이 1건만 롤백시켜 사람을 부른다
 * ({@link ErrorCode#PLAN_DATA_INVALID}). 원인이 요금제 설정 오류라 배치가 스스로 고칠 수 없기 때문이다.
 *
 * <p><b>멱등(#1145 §3-3)</b>: 강등이 끝난 구독은 EXPIRED 가 되고 신규 FREE 행은
 * {@code current_period_end = NULL} 이라, 다음 회차의 대상 조회 조건
 * ({@code status in LIVE_STATUSES AND current_period_end < threshold})에 더 이상 걸리지 않는다. 게다가
 * 이 메서드는 트랜잭션 안에서 대상 조건을 <b>다시 확인</b>하므로(아래 재검증), 스케줄러가 대상 id 를 읽은
 * 뒤 처리하기 전에 다른 경로(결제 승인·관리자 변경)가 먼저 전이시킨 경우에도 이중 강등하지 않는다.
 * 신규 ACTIVE 발급이 부분 UQ 를 밟으면 <b>그 한 줄에서 잡아</b>
 * {@link ErrorCode#PLAN_ACTIVE_SUBSCRIPTION_CONFLICT} 로 번역해 던진다 — 호출부(스케줄러)가 그 코드만
 * "이미 다른 경로가 활성 구독을 만듦"으로 스킵하고 다음 회차에 자연 재시도한다. 사용량 이월·좌석 정지
 * 등에서 나온 <b>그 외</b> 무결성 위반은 번역하지 않고 그대로 올려보내 실패로 집계되게 한다(조용한 skip 금지).
 *
 * <p><b>알림은 여기서 발행하지 않는다</b> — 강등이 실제로 커밋된 뒤에 나가야 하므로 호출부가
 * {@link PlanExpiryResult} 를 받아 트랜잭션 밖에서 발행한다(그 record javadoc 참고).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanExpiryWriter {

    /**
     * 강등 대상으로 인정하는 "살아있는 구독" 상태 — {@code QuotaService#findLivePlan}·
     * {@code PlanTransitionService#resolveCurrentUserPlan} 과 <b>같은 집합</b>이어야 한다(리뷰 P1).
     * {@code PlanExpiryScheduler} 가 조회에 쓰는 집합과도 동일하다.
     */
    public static final List<UserPlanStatus> LIVE_STATUSES =
            List.of(UserPlanStatus.ACTIVE, UserPlanStatus.UPGRADE_REQUESTED);

    private final CompanyRepository companyRepository;
    private final PlanRepository planRepository;
    private final UserPlanRepository userPlanRepository;
    private final PlanTransitionService planTransitionService;
    private final PlanDowngradeService planDowngradeService;
    private final ScheduledPlanChangeCanceller scheduledPlanChangeCanceller;
    private final PlanExpiryProperties properties;

    /**
     * 결제 주기가 만료된 구독 1건을 FREE 로 강등한다.
     *
     * @param userPlanId 강등 대상 구독 id(스케줄러가 조회한 것)
     * @param threshold  만료 판정 기준 시각({@code now - gracePeriod}) — 조회 시점과 <b>같은 값</b>을
     *                   넘겨야 재검증이 조회 조건과 일치한다
     * @return 강등 결과(스킵이면 {@link PlanExpiryResult#downgraded()} 가 false)
     * @throws BusinessException {@link ErrorCode#PLAN_ACTIVE_SUBSCRIPTION_CONFLICT} — 신규 ACTIVE 발급이
     *                           부분 UQ 를 밟은 경우(다른 경로가 먼저 활성 구독을 만듦). 호출부는 이
     *                           코드만 "경합 스킵"으로 처리하고 그 외 무결성 위반은 실패로 집계해야 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PlanExpiryResult expireToFreePlan(Long userPlanId, Instant threshold) {
        // 잠금 대기 상한을 트랜잭션 로컬로 건다(리뷰 NEW-2) — @Scheduled 기본 스레드 풀이 1개라 한 건의
        // 무한 대기가 다른 배치까지 멈춘다. JPA lock.timeout 힌트는 PostgreSQL 에서 무시되므로 쓰지 않는다
        // (UserPlanRepository#applyLockTimeout javadoc 에 근거).
        userPlanRepository.applyLockTimeout(Integer.toString(properties.getLockTimeoutMs()));

        // 행 잠금과 함께 읽는다(리뷰 P3-5) — 재검증이 보는 상태가 판정 시점의 확정 상태가 되도록,
        // 그리고 다중 인스턴스가 같은 구독을 동시에 강등하지 않도록 직렬화한다. 잠금으로도 남는 한계
        // (@Version 부재로 인한 lost update)는 UserPlanRepository#findByIdForUpdate javadoc 참고.
        UserPlan current = userPlanRepository.findByIdForUpdate(userPlanId).orElse(null);
        if (current == null) {
            // 조회~처리 사이에 사라진 행. 이론적으로 발생하지 않지만 배치를 죽이지 않는다.
            return PlanExpiryResult.skipped("구독 행 없음");
        }
        // ⚠️ 대상 조건 재검증 — 스케줄러의 조회는 잠금 없는 스냅샷이라, id 를 읽은 뒤 처리 전에 다른
        // 경로(결제 승인·관리자 플랜 변경)가 먼저 전이시켰을 수 있다. 그 상태에서 강등하면 방금 결제한
        // 유료 구독을 곧바로 내리게 된다.
        //
        // ⚠️ 판정 집합은 조회 쿼리(findExpiryTargetIds)·엔타이틀먼트 판정(QuotaService#findLivePlan)과
        // 반드시 같아야 한다(리뷰 P1). 여기만 ACTIVE 로 좁히면 UPGRADE_REQUESTED 구독이 조회에는 잡히고
        // 재검증에서 매번 스킵돼 강등이 영영 일어나지 않는다.
        if (!LIVE_STATUSES.contains(current.getStatus())) {
            return PlanExpiryResult.skipped("이미 유효 구독 아님(status=" + current.getStatus() + ")");
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

        // 이 구독에 걸린 하향 예약(#1105)을 무효화한다 — 세 전이 경로가 공유해야 하는 규칙이고, 여기만
        // 빠져 있으면 예약이 옛 user_plan_id 에 매달린 채 PENDING 으로 남아 owner 가 취소할 수도 없는
        // 유령 예약이 된다(조회·취소가 모두 현재 구독 id 기준이라 화면에서는 사라진다 — 보안 리뷰 P2-3).
        // ⚠️ 위 재검증(상태·주기)을 <b>통과한 뒤</b>여야 한다 — "결제 주기가 갱신됨" 스킵 경로에서 취소하면
        // 아직 유효한 예약을 잘못 없앤다. 잠금 순서에 대한 근거는 ScheduledPlanChangeCanceller javadoc 참고.
        scheduledPlanChangeCanceller.cancelOnTransition(current.getId(), "구독 만료 강등으로 예약이 무효화됨");

        current.expire();
        userPlanRepository.saveAndFlush(current);

        UserPlan renewed = companyId != null
                ? UserPlan.forCompany(companyId, freePlan.getId())
                : UserPlan.forUser(userId, freePlan.getId());
        // 결제가 아니라 만료에 의한 전이다 — 주기 시작은 승계하고 종료는 대상이 무료면 NULL(무기한).
        // 유료 여부 판정은 AdminPlanService#changePlan 과 <b>같은 근거</b>(plans.price_monthly)를 쓴다
        // (리뷰 P3-2) — 여기서 false 를 하드코딩하면 결과는 같아도 판정 근거가 갈라져, 나중에 FREE 가격
        // 정책이 바뀌면 두 경로가 조용히 어긋난다.
        renewed.carryOverBillingPeriod(current, isPaid(freePlan));
        if (renewed.getCurrentPeriodEnd() != null) {
            // ⚠️ 안전 불변식(리뷰 NEW-1) — FREE 로 내리는데 주기가 남으면 이 구독이 다음 회차 대상 조건을
            // 즉시 다시 만족해 매일 밤 강등·알림·행 증식이 무한 반복된다. 여기까지 왔다는 건 FREE 요금제에
            // 양수 가격이 설정됐다는 뜻이고, 그건 배치가 스스로 고칠 수 없는 요금제 설정 오류다.
            // 이 1건만 롤백하고 사람을 부른다(스케줄러가 ErrorCode 를 WARN 으로 남긴다).
            throw new BusinessException(ErrorCode.PLAN_DATA_INVALID);
        }
        UserPlan saved;
        try {
            // ⚠️ try 범위를 이 한 줄로 좁힌다(AdminPlanService·PlanTransitionService 의 동일 주석과 같은
            // 이유, 리뷰 P2-1) — 아래 사용량 이월·좌석 정지에서 나온 무결성 위반까지 "이미 활성 구독
            // 존재"로 오분류하면, 진짜 데이터 결함이 매일 밤 INFO 스킵 한 줄로 사라진다.
            saved = userPlanRepository.saveAndFlush(renewed);
        } catch (DataIntegrityViolationException e) {
            // 동시 전이 경합 — 다른 경로(결제 승인·관리자 변경)가 먼저 ACTIVE 를 만들어 부분 UQ
            // (uq_user_plans_active_company/user) 를 밟았다. 호출부가 이 코드만 보고 "경합 스킵"으로
            // 처리하고 다음 회차에 자연 재시도한다.
            throw new BusinessException(ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT);
        }

        planTransitionService.carryOverUsage(current.getId(), saved.getId());

        List<Long> suspendedUserIds = List.of();
        if (overflow != null) {
            planDowngradeService.applyOverflow(companyId, freePlan, overflow);
            suspendedUserIds = List.copyOf(overflow.seatUserIdsToSuspend());
        }

        // 운영 로그(#1145 §5) — 누가(companyId/userId) 어느 플랜에서 언제 내려갔는지.
        // ⚠️ 정지된 좌석의 user id 를 반드시 남긴다(리뷰 P2-3). 오강등이 발생하면 되돌릴 대상 목록을
        // 이 로그 말고는 복원할 방법이 없다(users.status 는 이전 값을 보관하지 않는다). id 만 남기므로
        // 개인정보(이메일·이름)는 로그에 들어가지 않는다.
        log.info("구독 만료 FREE 강등 — companyId={} userId={} previousPlan={} periodEnd={} "
                        + "expiredAt={} suspendedSeats={} suspendedUserIds={}",
                companyId, userId, currentPlan.getName(), periodEnd, Instant.now(),
                suspendedUserIds.size(), suspendedUserIds);

        return PlanExpiryResult.downgraded(recipientUserId, currentPlan.getName(), companyId, userId,
                periodEnd, suspendedUserIds);
    }

    /**
     * 유료 요금제인가 — {@code AdminPlanService#priceOrZero} 와 같은 기준({@code plans.price_monthly}).
     * 티어 이름이 아니라 가격이 유일한 기준인 이유는 그쪽 javadoc 과 같다(가격은 플랫폼 관리자가 바꿀 수
     * 있어 이름 순서와 어긋날 수 있다). null 가격은 0원으로 본다.
     */
    private boolean isPaid(Plan plan) {
        BigDecimal price = plan.getPriceMonthly();
        return price != null && price.signum() > 0;
    }

    private Long resolveCompanyOwnerUserId(Long companyId) {
        return companyRepository.findById(companyId)
                .map(Company::getOwnerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
    }
}
