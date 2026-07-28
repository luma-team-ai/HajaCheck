package com.hajacheck.membership.service;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.config.ScheduledPlanChangeProperties;
import com.hajacheck.membership.dto.DowngradeOverflow;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.ScheduledPlanChange;
import com.hajacheck.membership.entity.ScheduledPlanChangeStatus;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.ScheduledPlanChangeRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약된 플랜 하향 1건을 실제로 적용하는 <b>DB 트랜잭션 전담</b> 빈(#1105 / HAJA-526).
 *
 * <p>{@code ScheduledPlanChangeScheduler}(조회·순회 담당)와 별도 빈으로 둔다 — self-invocation 으로
 * {@code @Transactional} 프록시가 풀리는 것을 막기 위해서다({@link PlanExpiryWriter}·{@code PaymentWriter}
 * 와 동일한 이유). <b>건별 독립 트랜잭션({@code REQUIRES_NEW})</b>이라 한 예약의 실패가 같은 회차의 나머지
 * 예약 처리를 막지 않는다.
 *
 * <p><b>⚠️ 전이 로직을 새로 짜지 않는다</b>. 하향은 이미 관리자 콘솔({@code AdminPlanService#changePlan})과
 * 만료 강등({@link PlanExpiryWriter})이 쓰는 경로가 있고, 세 경로가 갈라지면 정지 규칙이 어긋난다.
 * 그래서 순서·구성 요소를 그대로 재사용한다: {@link PlanDowngradeService#preview}(쓰기 이전에 정확히 한
 * 번) → {@link UserPlan#expire()} → flush → 신규 ACTIVE 발급 → {@link PlanTransitionService#carryOverUsage}
 * → {@link PlanDowngradeService#applyOverflow}.
 *
 * <p><b>결제 주기(#1104)만 다르다</b>: 만료 강등은 주기가 끝나 FREE 로 내리는 것이라 주기를 NULL 로
 * 비우지만, 예약 하향은 <b>다음 주기의 시작</b>이다. 그래서 대상이 유료면
 * {@link UserPlan#startNewBillingPeriod}{@code (effectiveAt)} 로 <b>새 주기를 연다</b> — 여기서
 * {@code carryOverBillingPeriod} 로 이미 지나간 만료일을 승계하면, 새 유료 구독이 발급되는 즉시 만료
 * 강등 배치({@code PlanExpiryScheduler})의 대상이 되어 하루 만에 FREE 로 떨어진다(예약 기능이 통째로
 * 무의미해진다). 대상이 무료면 {@code carryOverBillingPeriod(current, false)} 로 주기 종료를
 * NULL(무기한)로 둔다(#1104 규칙).
 *
 * <p><b>멱등</b>: 상태 전이는 {@code UPDATE ... WHERE status = 'PENDING'}
 * ({@link ScheduledPlanChangeRepository#markApplied}) 조건부로만 하고, <b>갱신 행 수가 1일 때만</b> 실제
 * 쓰기를 진행한다. 예약 행은 PESSIMISTIC_WRITE 로 잠그고 대상 조건을 트랜잭션 안에서 다시 확인하며,
 * 중복 <b>예약</b> 자체는 부분 UQ({@code uq_scheduled_plan_changes_pending})가 막는다.
 *
 * <p><b>⚠️ 저장된 {@code keep_user_ids} 는 반드시 실행 시점에 재검증한다</b>(#947 흡수) — 예약은 이
 * 목록을 한 달 가까이 보관하므로 그 사이 퇴사·정지된 id 가 섞일 수 있다. 그대로 넘기면
 * {@link PlanDowngradeService#preview} 의 스코프 검증({@code PLAN_KEEP_USER_INVALID})에 걸려 예약이
 * 통째로 죽는다. {@link #resolveKeepUserIds} 가 무효 id 를 드롭하고 부족분을 자동 규칙(owner + id
 * 오름차순)으로 보충한 뒤, ACTIVE ADMIN 잔존 불변식은 {@code PlanDowngradeService
 * #requireSurvivingActiveAdmin} 이 그대로 재확인한다.
 *
 * <p><b>알려진 한계(#913)</b>: 이 트랜잭션은 예약 행과 구독 행만 잠그고, {@code preview} 가 읽는 활성
 * 구성원 목록은 잠금 없는 스냅샷이다. 같은 시각 다른 트랜잭션이 "구 플랜" 기준으로 좌석을 활성화하면
 * ({@code QuotaService#reserveSeat} 는 구 userPlan 의 usage_counters 행을 잠그므로 이 트랜잭션과
 * 직렬화되지 않는다) 커밋 후 새 플랜 한도를 넘는 인원이 남을 수 있다. 이는 즉시 하향
 * ({@code AdminPlanService#changePlan})에도 이미 존재하는 노출이지 이 기능이 새로 만드는 것이 아니며,
 * 해소는 #913 의 별도 과제다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledPlanChangeWriter {

    private final ScheduledPlanChangeRepository scheduledPlanChangeRepository;
    private final UserPlanRepository userPlanRepository;
    private final PlanRepository planRepository;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PlanDowngradeService planDowngradeService;
    private final PlanTransitionService planTransitionService;
    private final ScheduledPlanChangeProperties properties;

    /**
     * 예약 1건을 적용한다.
     *
     * @param scheduledChangeId 실행 대상 예약 id(스케줄러가 조회한 것)
     * @param now               실행 기준 시각 — 조회 시점과 <b>같은 값</b>을 넘겨야 재검증이 조회 조건과
     *                          일치한다
     * @return 실행 결과(적용/무효취소/스킵)
     * @throws BusinessException 도메인 위반. 호출부는
     *                           {@link ErrorCode#PLAN_ACTIVE_SUBSCRIPTION_CONFLICT} 만 "경합 스킵"
     *                           (상태를 PENDING 으로 남겨 다음 회차 재시도)으로 처리하고, 그 외에는
     *                           예약을 FAILED 로 종료시켜야 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ScheduledPlanChangeResult applyDueChange(Long scheduledChangeId, Instant now) {
        // 잠금 대기 상한을 트랜잭션 로컬로 건다 — @Scheduled 기본 스레드 풀이 1개라 한 건의 무한 대기가
        // 다른 배치까지 멈춘다(UserPlanRepository#applyLockTimeout javadoc 에 근거). 이 설정은 세션이
        // 아니라 트랜잭션 스코프라 어느 리포지토리로 호출하든 같은 커넥션·같은 트랜잭션에 적용된다.
        userPlanRepository.applyLockTimeout(Integer.toString(properties.getLockTimeoutMs()));

        // 예약 행 → 구독 행 순서로 잠근다. 항상 이 순서로만 잡으므로 다른 배치와 잠금 순환이 생기지 않는다
        // (PlanExpiryWriter 는 구독 행만 잠근다).
        ScheduledPlanChange scheduled = scheduledPlanChangeRepository
                .findByIdForUpdate(scheduledChangeId).orElse(null);
        if (scheduled == null) {
            // 조회~처리 사이에 사라진 행. 이론적으로 발생하지 않지만 배치를 죽이지 않는다.
            return ScheduledPlanChangeResult.skipped("예약 행 없음");
        }
        if (scheduled.getStatus() != ScheduledPlanChangeStatus.PENDING) {
            return ScheduledPlanChangeResult.skipped("이미 처리된 예약(status=" + scheduled.getStatus() + ")");
        }
        Instant effectiveAt = scheduled.getEffectiveAt();
        if (effectiveAt.isAfter(now)) {
            // 조회 조건이 effective_at <= now 이므로 정상 경로에서는 도달하지 않지만, 잠금 대기 중
            // 시계가 흔들리거나 호출부가 다른 기준시각을 넘긴 경우를 방어한다(조기 적용 절대 금지).
            return ScheduledPlanChangeResult.skipped("아직 적용 시각 전");
        }

        Long userPlanId = scheduled.getUserPlanId();
        Long targetPlanId = scheduled.getTargetPlanId();
        List<Long> storedKeepUserIds = scheduled.keepUserIdList();

        UserPlan current = userPlanRepository.findByIdForUpdate(userPlanId).orElse(null);
        if (current == null) {
            return cancelObsolete(scheduledChangeId, "예약 대상 구독 행 없음");
        }
        // ⚠️ 예약 생성 이후 그 구독이 다른 경로(결제 승인·즉시 변경·만료 강등)로 전이됐을 수 있다.
        // 그 상태에서 적용하면 방금 결제한 구독을 내리거나, 이미 EXPIRED 인 행을 또 만료시키게 된다.
        // 판정 집합은 엔타이틀먼트 판정(QuotaService#findLivePlan)과 같아야 한다(PlanExpiryWriter 와 동일).
        if (!PlanExpiryWriter.LIVE_STATUSES.contains(current.getStatus())) {
            return cancelObsolete(scheduledChangeId,
                    "예약 생성 이후 구독이 전이됨(status=" + current.getStatus() + ")");
        }

        Plan targetPlan = planRepository.findById(targetPlanId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
        Plan currentPlan = planRepository.findById(current.getPlanId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
        // ⚠️ "여전히 하향인가"를 다시 판정한다 — 예약 이후 결제로 요금제가 바뀌었거나 플랫폼 관리자가
        // 가격 정책을 바꿨을 수 있다(#624 Plan#updatePolicy). 판정 기준은 생성 시점·즉시 변경 경로
        // (AdminPlanService#requireNotUpgrade)와 같은 plans.price_monthly 다 — 근거가 갈라지면 두 경로가
        // 조용히 어긋난다. 하향이 아니게 됐으면 이 예약은 의미를 잃었으므로 무효 처리한다(무결제 상향을
        // 배치가 대신 실행해 주는 우회로가 되면 안 된다 — #988 이 막은 그 경로다).
        if (priceOrZero(targetPlan).compareTo(priceOrZero(currentPlan)) >= 0) {
            return cancelObsolete(scheduledChangeId,
                    "더 이상 하향이 아님(current=" + currentPlan.getName() + " target=" + targetPlan.getName() + ")");
        }

        Long companyId = current.getCompanyId();
        Long userId = current.getUserId();
        // 알림 수신자는 쓰기 이전에 확정한다 — 회사 행을 못 찾으면 좌석 정지 계산도 어차피 실패하므로,
        // 부분 상태를 남기지 않도록 여기서 먼저 끊는다(PlanExpiryWriter 와 동일 순서).
        Long recipientUserId = companyId != null ? resolveCompanyOwnerUserId(companyId) : userId;

        // ⚠️ preview 는 쓰기 이전에 정확히 한 번(PlanDowngradeService F-7) — 결과를 그대로 applyOverflow 에
        // 재사용한다. 개인 구독(companyId=null)은 좌석·회사 시설물 개념이 없어 계산 자체를 건너뛴다.
        List<Long> keepUserIds = companyId != null
                ? resolveKeepUserIds(scheduledChangeId, companyId, targetPlan, storedKeepUserIds)
                : List.of();
        DowngradeOverflow overflow = companyId != null
                ? planDowngradeService.preview(companyId, currentPlan, targetPlan, keepUserIds)
                : null;

        // ⚠️ 점유(claim) — 여기가 멱등성의 핵심이다. 조건부 UPDATE 가 1행을 갱신했을 때만 "이번 실행이
        // 이 예약을 가져갔다"고 인정한다. 행 잠금이 이미 직렬화하고 있지만, 잠금은 트랜잭션 경계 안에서만
        // 유효하고 이 UPDATE 는 <b>상태 자체</b>로 재실행을 막는다(다중 인스턴스·수동 재호출 포함).
        // 검증을 전부 통과한 뒤에 점유하므로, 무효로 판정된 예약이 APPLIED 로 남는 일도 없다.
        int claimed = scheduledPlanChangeRepository.markApplied(scheduledChangeId, now);
        if (claimed != 1) {
            return ScheduledPlanChangeResult.skipped("다른 실행이 이미 점유함");
        }
        // markApplied 는 벌크 UPDATE 라 영속성 컨텍스트를 비운다(1차 캐시에 남은 PENDING 스냅샷이 이후
        // 조회에 섞이면 안 되기 때문). 그래서 구독 엔티티를 여기서 다시 영속 상태로 읽는다 — 행 잠금은
        // 트랜잭션 스코프라 그대로 유지되므로 재조회로 경합 창이 열리지 않는다.
        UserPlan reloaded = userPlanRepository.findById(userPlanId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));

        // 기존 구독 만료 후 신규 ACTIVE 발급 — 부분 UQ(uq_user_plans_active_company/user: ACTIVE 최대
        // 1건)를 만족하도록 만료 UPDATE 를 먼저 flush 한 뒤 INSERT 한다(세 경로 공통 순서).
        reloaded.expire();
        userPlanRepository.saveAndFlush(reloaded);

        UserPlan renewed = companyId != null
                ? UserPlan.forCompany(companyId, targetPlan.getId())
                : UserPlan.forUser(userId, targetPlan.getId());
        applyBillingPeriod(renewed, reloaded, targetPlan, effectiveAt);

        UserPlan saved;
        try {
            // try 범위를 이 한 줄로 좁힌다 — 아래 사용량 이월·좌석 정지에서 나온 무결성 위반까지 "이미
            // 활성 구독 존재"로 오분류하면 진짜 데이터 결함이 스킵 한 줄로 사라진다(세 경로 공통 주석).
            saved = userPlanRepository.saveAndFlush(renewed);
        } catch (DataIntegrityViolationException e) {
            // 동시 전이 경합 — 다른 경로가 먼저 ACTIVE 를 만들었다. 호출부가 이 코드만 보고 예약을
            // PENDING 으로 남겨 다음 회차에 자연 재시도한다(이 트랜잭션은 통째로 롤백되므로 점유도 풀린다).
            throw new BusinessException(ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT);
        }

        // 당월 사용량 이월(#851) — 이월하지 않으면 예약 하향이 곧 월 분석 한도 리셋이 되어 한도 강제
        // (#843)가 무력화된다. 세 전이 경로가 반드시 같은 규칙을 적용해야 한다.
        planTransitionService.carryOverUsage(reloaded.getId(), saved.getId());

        List<Long> suspendedUserIds = List.of();
        if (overflow != null) {
            // 초과 좌석 정지는 신규 구독 발급과 같은 트랜잭션에서 — 플랜만 내려가고 정지가 안 되면
            // 한도가 조용히 무력화된다. 위에서 계산해 둔 overflow 를 그대로 적용할 뿐 재계산하지 않는다.
            planDowngradeService.applyOverflow(companyId, targetPlan, overflow);
            suspendedUserIds = List.copyOf(overflow.seatUserIdsToSuspend());
        }

        // 운영 로그 — 누가(companyId/userId) 어느 플랜에서 어디로 언제 내려갔는지. ⚠️ 정지된 좌석의
        // user id 를 반드시 남긴다(PlanExpiryWriter 리뷰 P2-3 과 같은 이유): 오적용을 되돌릴 대상 목록을
        // 이 로그 말고는 복원할 방법이 없다. id 만 남기므로 개인정보는 로그에 들어가지 않는다.
        log.info("예약 하향 적용 — scheduledChangeId={} companyId={} userId={} {}→{} effectiveAt={} "
                        + "appliedAt={} suspendedSeats={} suspendedUserIds={}",
                scheduledChangeId, companyId, userId, currentPlan.getName(), targetPlan.getName(),
                effectiveAt, now, suspendedUserIds.size(), suspendedUserIds);

        return ScheduledPlanChangeResult.applied(recipientUserId, currentPlan.getName(),
                targetPlan.getName(), companyId, userId, effectiveAt, suspendedUserIds);
    }

    /**
     * 예약을 FAILED 로 종료한다 — <b>실행 트랜잭션과 별개의 트랜잭션</b>에서 실행해야 하므로 별도
     * 메서드({@code REQUIRES_NEW})로 둔다. 실패한 실행은 롤백되므로 같은 트랜잭션에 실으면 실패 기록까지
     * 함께 사라진다.
     *
     * @return 실제로 FAILED 로 바뀌었는지(이미 다른 상태면 false)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(Long scheduledChangeId, String failureReason) {
        return scheduledPlanChangeRepository.markFailed(scheduledChangeId, failureReason) == 1;
    }

    /**
     * 신규 구독의 결제 주기를 정한다(#1104).
     *
     * <p><b>유료 대상이면 새 주기를 연다</b>({@code effectiveAt} 부터 1개월). 예약 하향의 적용 시점은
     * 이전 주기가 <b>끝나는</b> 순간이고 그때부터가 새 주기이기 때문이다. 여기서 이전 주기를 승계하면
     * 이미 지나간 만료일이 그대로 실려 새 유료 구독이 즉시 {@code PlanExpiryScheduler} 의 강등 대상이
     * 된다 — 예약 기능이 하루 만에 무의미해진다.
     *
     * <p><b>무료 대상이면 종료를 NULL(무기한)로</b> 둔다({@code carryOverBillingPeriod(previous, false)}).
     * FREE 에 만료일이 남으면 마이페이지가 "무료인데 다음 결제일"을 표시하고 만료 강등 배치가 그 행을
     * 다시 대상으로 잡는다(#1104 규칙). 유료 여부 판정 근거는 다른 두 경로와 같은
     * {@code plans.price_monthly} 다.
     *
     * <p>⚠️ 알려진 한계: 배치가 한 달 넘게 멈춰 있다가 돌면 {@code effectiveAt + 1개월} 이 이미 과거라
     * 새 유료 구독이 곧바로 만료 대상이 된다. 그 경우 만료 강등 배치가 FREE 로 내리는 것이 오히려 맞는
     * 결과(그 주기의 요금을 받지 않았다)라 별도 보정을 두지 않는다.
     */
    private void applyBillingPeriod(UserPlan renewed, UserPlan previous, Plan targetPlan, Instant effectiveAt) {
        if (isPaid(targetPlan)) {
            renewed.startNewBillingPeriod(effectiveAt);
            return;
        }
        renewed.carryOverBillingPeriod(previous, false);
    }

    /**
     * 저장된 {@code keep_user_ids} 를 <b>실행 시점 기준</b>으로 재검증한다(#947 흡수, 이 클래스 javadoc).
     *
     * <ol>
     *   <li>지금 이 회사의 ACTIVE 구성원이 아닌 id(퇴사·정지·초대 대기·타 회사)를 <b>드롭</b>한다 —
     *       그대로 넘기면 {@code PlanDowngradeService#validateKeepUserIdsScope} 가
     *       {@code PLAN_KEEP_USER_INVALID} 로 예약을 통째로 죽인다.</li>
     *   <li>드롭으로 좌석이 남으면 <b>자동 규칙</b>(owner 우선 → id 오름차순)으로 한도까지 보충한다.
     *       보충하지 않으면 관리자가 고르지도 않은 사람이 정지된다.</li>
     *   <li>선택이 통째로 비면 빈 리스트를 돌려준다 — {@code PlanDowngradeService} 가 "선택 없음 = 자동
     *       규칙"으로 처리하는 기존 계약을 그대로 탄다.</li>
     * </ol>
     *
     * <p>ACTIVE ADMIN 잔존 불변식({@code requireSurvivingActiveAdmin})은 여기서 흉내내지 않고
     * {@code PlanDowngradeService} 가 그대로 재확인한다 — 같은 규칙을 두 곳에 복제하면 어긋난다.
     *
     * <p>좌석 한도가 예약 이후 <b>더 좁아진</b> 경우(플랫폼 관리자의 정책 변경)는 여기서 임의로 잘라내지
     * 않는다. 보정하면 관리자가 지키기로 한 사람이 말없이 정지되므로, {@code PlanDowngradeService} 가
     * {@code PLAN_SEAT_QUOTA_EXCEEDED} 로 거절하게 두고 예약은 FAILED 로 종료시켜 사람을 부른다
     * (그 결과 회사는 상위 요금제를 유지하므로 아무도 잘못 정지되지 않는다 — fail-safe 방향이다).
     */
    private List<Long> resolveKeepUserIds(Long scheduledChangeId, Long companyId, Plan targetPlan,
            List<Long> storedKeepUserIds) {
        if (storedKeepUserIds.isEmpty()) {
            return List.of();
        }
        List<User> active = userRepository.findByCompanyIdAndStatusOrderByIdAsc(
                companyId, UserStatus.ACTIVE, Pageable.unpaged());
        Set<Long> activeIds = active.stream().map(User::getId).collect(Collectors.toSet());

        LinkedHashSet<Long> keep = storedKeepUserIds.stream()
                .filter(activeIds::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int dropped = storedKeepUserIds.size() - keep.size();
        if (keep.isEmpty()) {
            log.warn("예약 하향 유지 대상 전원 무효 — scheduledChangeId={} companyId={} storedCount={} "
                            + "자동 규칙(owner + id 오름차순)으로 대체한다",
                    scheduledChangeId, companyId, storedKeepUserIds.size());
            return List.of();
        }

        Integer maxSeats = targetPlan.getMaxSeats();
        if (maxSeats != null && keep.size() < maxSeats) {
            // owner 는 PlanDowngradeService 가 어차피 항상 유지하므로, 좌석 수 계산이 어긋나지 않도록
            // 보충도 owner 부터 채운다(그쪽 resolveSeatsToSuspend 와 같은 우선순위).
            companyRepository.findById(companyId)
                    .map(Company::getOwnerUserId)
                    .filter(activeIds::contains)
                    .ifPresent(keep::add);
            for (User user : active) {
                if (keep.size() >= maxSeats) {
                    break;
                }
                keep.add(user.getId());
            }
        }
        if (dropped > 0) {
            log.warn("예약 하향 유지 대상 재검증 — scheduledChangeId={} companyId={} 무효 {}건 드롭, "
                            + "최종 유지 {}건(자동 보충 포함)",
                    scheduledChangeId, companyId, dropped, keep.size());
        }
        return List.copyOf(keep);
    }

    /**
     * 예약이 의미를 잃었을 때 CANCELED 로 종료한다. FAILED 로 남기지 않는 이유: 사람이 고칠 것이 없고
     * (구독이 이미 다른 경로로 전이됐을 뿐이다) 정상적인 종료라, 실패로 집계하면 회차 요약 WARN 이
     * 일상적으로 켜져 진짜 실패 신호가 희석된다.
     */
    private ScheduledPlanChangeResult cancelObsolete(Long scheduledChangeId, String reason) {
        scheduledPlanChangeRepository.cancelPendingById(scheduledChangeId, reason);
        return ScheduledPlanChangeResult.canceled(reason);
    }

    private Long resolveCompanyOwnerUserId(Long companyId) {
        return companyRepository.findById(companyId)
                .map(Company::getOwnerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
    }

    /**
     * 유료 요금제인가 — {@code AdminPlanService#priceOrZero}·{@code PlanExpiryWriter#isPaid} 와 같은
     * 기준({@code plans.price_monthly}). 티어 이름이 아니라 가격이 유일한 기준인 이유는 그쪽 javadoc 과
     * 같다(가격은 플랫폼 관리자가 바꿀 수 있어 이름 순서와 어긋날 수 있다). null 가격은 0원으로 본다.
     */
    private boolean isPaid(Plan plan) {
        return priceOrZero(plan).signum() > 0;
    }

    private BigDecimal priceOrZero(Plan plan) {
        return plan.getPriceMonthly() == null ? BigDecimal.ZERO : plan.getPriceMonthly();
    }
}
