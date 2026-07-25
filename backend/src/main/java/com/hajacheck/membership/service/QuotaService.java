package com.hajacheck.membership.service;

import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플랜 한도 강제 + 사용량 집계(#843 / HAJA-441) — {@code usage_counters} 에 실제로 쓰는 유일한 프로덕션 경로.
 *
 * <h2>왜 HandlerInterceptor 가 아닌가</h2>
 * 이름이 "QuotaInterceptor" 지만 실제 형태는 <b>서비스 계층 명시 호출</b>이다. 한도 검사·차감은 자원을
 * 실제로 만드는 비즈니스 트랜잭션과 <b>같은 경계</b> 안에 있어야 한다 —
 * <ul>
 *   <li>스냅샷 한도(시설물·좌석)는 "행 잠금 → 실측 → 조건부 UPDATE → 자원 INSERT" 가 한 트랜잭션이어야
 *       다음 요청의 실측이 방금 만든 자원을 포함해 세고, 롤백 시 예약도 자동으로 사라진다.</li>
 *   <li>HTTP {@code HandlerInterceptor} 는 트랜잭션 밖(preHandle/afterCompletion)이라 실패 보상을 손으로
 *       짜야 하고, 요청 본문에 의존하는 증분(분석 요청의 이미지 장수)을 알 수도 없다.</li>
 *   <li>AOP({@code @Aspect})는 이 레포에 도입 사례가 0건이라 새 인프라를 들이는 비용 대비 이점이 없고,
 *       프록시 경계 문제(self-invocation)를 새로 만든다.</li>
 * </ul>
 *
 * <h2>카운터 성격 구분</h2>
 * <ul>
 *   <li><b>누적(월별)</b> — {@code analyzed_image_count}/{@code analysis_request_count}: 실측 원천이 없다.
 *       저장된 값을 조건으로 쓰는 순수 원자적 조건부 UPDATE(0행 = 한도 초과) + 실패 시 보상 차감.</li>
 *   <li><b>스냅샷(현재 보유량)</b> — {@code facility_count}/{@code seat_count}: DDL 주석부터
 *       "해당 월 <b>또는 집계 시점의</b> 등록 시설 수 / 사용 좌석 수" 로 스냅샷을 의도한다. 월 누적이 아니므로
 *       {@code facilities}/{@code users} 실측 count(*)를 진실의 원천으로 삼고, 집계 행 잠금 아래에서
 *       재측정한 값으로 조건부 UPDATE 한다. 카운터는 화면 표시용 미러다 — 증감 누락으로 인한 드리프트가
 *       구조적으로 발생하지 않는다(시설물 삭제·구성원 정지 같은 감소 경로마다 보상 코드를 심을 필요 없음).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuotaService {

    // usage_counters.period 존 — MembershipService/AdminPlanService 와 동일하게 KST 고정(#711).
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UserPlanRepository userPlanRepository;
    private final PlanRepository planRepository;
    private final UsageCounterRepository usageCounterRepository;
    private final PlanProvisioningService planProvisioningService;
    private final FacilityRepository facilityRepository;
    private final UserRepository userRepository;

    /**
     * 시설물 1건 등록 슬롯 예약 — <b>시설물 INSERT 와 같은 트랜잭션</b>에서 호출해야 한다.
     * 그 계약을 관례가 아니라 런타임에서 fail-fast 시키기 위해 {@code MANDATORY} 로 선언한다(호출부에
     * 활성 트랜잭션이 없으면 즉시 IllegalTransactionStateException — 잠금이 곧바로 풀려 한도가 새는
     * 사용법을 조용히 통과시키지 않는다).
     *
     * <p>기존 데이터가 이미 한도를 넘겨 있어도(FREE 회사에 시설물 11건) 막는 것은 <b>신규 등록뿐</b>이다 —
     * 조회·수정·삭제 경로는 이 검사를 타지 않으므로 기존 기능이 잠기지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveFacilitySlot(Long userId, Long companyId) {
        UserPlan userPlan = resolveLivePlan(userId, companyId);
        Plan plan = findPlan(userPlan.getPlanId());
        LocalDate period = currentPeriod();
        long usageCounterId = lockPeriodRow(userPlan, period, companyId);

        int measured = measureFacilities(companyId);
        Integer maxFacilities = plan.getMaxFacilities();
        if (maxFacilities == null) {
            // null = 무제한(Plan javadoc) — 조건절을 넣지 않고 표시값만 맞춘다.
            usageCounterRepository.syncFacilityCount(usageCounterId, measured + 1);
            return;
        }
        if (usageCounterRepository.reserveFacilitySlot(usageCounterId, measured, maxFacilities) == 0) {
            throw new BusinessException(ErrorCode.PLAN_FACILITY_QUOTA_EXCEEDED);
        }
    }

    /**
     * 시설물 삭제 후 보유량 재동기화 — 시설물은 물리 삭제(soft delete 미적용)라 실측값이 곧바로 줄어든다.
     * 표시값이 영구히 부풀어 한도를 잘못 막지 않도록 삭제 트랜잭션 안에서 함께 내린다.
     *
     * <p>삭제는 한도와 무관한 동작이므로 구독이 없거나 집계 행이 없으면 조용히 건너뛴다 — 사용량 미러
     * 갱신 실패가 삭제 자체를 막아서는 안 된다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void syncFacilityUsage(Long userId, Long companyId) {
        Optional<UserPlan> userPlan = findLivePlan(userId, companyId);
        if (userPlan.isEmpty()) {
            return;
        }
        Optional<Long> usageCounterId =
                usageCounterRepository.lockPeriodRow(userPlan.get().getId(), currentPeriod());
        if (usageCounterId.isEmpty()) {
            return;
        }
        usageCounterRepository.syncFacilityCount(usageCounterId.get(), measureFacilities(companyId));
    }

    /**
     * 좌석 1석 예약 — <b>구성원 활성화(ACTIVE 전이)와 같은 트랜잭션</b>에서 호출해야 한다
     * ({@link #reserveFacilitySlot} 과 같은 이유로 {@code MANDATORY}).
     * 실측 기준은 {@code MembershipService#getSeats} 와 동일한 "회사 소속 ACTIVE 사용자 수"다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveSeat(Long companyId) {
        UserPlan userPlan = resolveLivePlan(null, companyId);
        Plan plan = findPlan(userPlan.getPlanId());
        LocalDate period = currentPeriod();
        long usageCounterId = lockPeriodRow(userPlan, period, companyId);

        int measured = measureSeats(companyId);
        Integer maxSeats = plan.getMaxSeats();
        if (maxSeats == null) {
            usageCounterRepository.syncSeatCount(usageCounterId, measured + 1);
            return;
        }
        if (usageCounterRepository.reserveSeat(usageCounterId, measured, maxSeats) == 0) {
            throw new BusinessException(ErrorCode.PLAN_SEAT_QUOTA_EXCEEDED);
        }
    }

    /**
     * 월 분석 한도 차감(요청 1건 = 이미지 {@code imageCount} 장).
     *
     * <p>호출부(분석 시작)는 트랜잭션 밖이라 이 메서드가 독립 트랜잭션으로 커밋된다 — 이후 단계가 실패하면
     * 호출부가 반드시 {@link #refundAnalysisQuota} 로 보상해야 한다.
     */
    @Transactional
    public void consumeAnalysisQuota(Long userId, Long companyId, int imageCount) {
        if (imageCount <= 0) {
            return;
        }
        UserPlan userPlan = resolveLivePlan(userId, companyId);
        Plan plan = findPlan(userPlan.getPlanId());
        LocalDate period = currentPeriod();
        ensurePeriodRow(userPlan, period, companyId);

        Integer maxImages = plan.getMaxMonthlyAnalyses();
        int updated = maxImages == null
                ? usageCounterRepository.consumeAnalysisQuotaUnlimited(userPlan.getId(), period, imageCount)
                : usageCounterRepository.consumeAnalysisQuota(userPlan.getId(), period, imageCount, maxImages);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.PLAN_ANALYSIS_QUOTA_EXCEEDED);
        }
    }

    /**
     * 분석 시작·실행이 실패했을 때의 보상 차감.
     *
     * <p>{@code REQUIRES_NEW} 인 이유: 호출부는 대부분 "원래 실패"를 처리하는 예외 경로다. 보상을 호출부
     * 트랜잭션에 태우면 보상 쪽 실패가 호출부 트랜잭션까지 롤백 전용으로 오염시킨다.
     *
     * <p>⚠️ 그 대가로 <b>되돌릴 차감이 이미 커밋돼 있어야 한다</b> — 독립 트랜잭션이라 호출부의 미커밋
     * 차감은 보이지 않아 조용히 0행 갱신으로 끝난다. 실제 두 호출부는 모두 이 전제를 만족한다:
     * {@code InspectionAnalysisService#startAnalysis} 와 {@code InspectionAnalysisWorker#runAsync} 는
     * 둘 다 트랜잭션 밖이라 {@link #consumeAnalysisQuota} 가 자기 트랜잭션으로 즉시 커밋된 뒤에 실패
     * 경로로 들어간다.
     *
     * <p>⚠️ 이 메서드는 예외를 <b>삼키지 않는다</b>. 예전에는 본문을 try/catch 로 감쌌지만, 그러면
     * 프록시의 커밋 단계에서 새로 던져지는 {@code UnexpectedRollbackException} 은 못 잡아 원래 원인
     * ({@code ANALYSIS_QUEUE_FULL} 등)을 500 으로 덮어쓴다(리뷰 P2). 대신 <b>호출부가</b>
     * {@code catch (RuntimeException)} 로 감싸 경고만 남기고 원래 예외를 그대로 전파해야 한다 —
     * 보상 실패의 최악 결과는 사용량이 조금 과대 집계되는 것뿐이라 원인을 가릴 이유가 없다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refundAnalysisQuota(Long userId, Long companyId, int imageCount) {
        if (imageCount <= 0) {
            return;
        }
        findLivePlan(userId, companyId).ifPresent(userPlan ->
                usageCounterRepository.refundAnalysisQuota(userPlan.getId(), currentPeriod(), imageCount));
    }

    /**
     * 당월 집계 행을 보장한 뒤 배타 잠금한다. 잠금을 먼저 잡아야 뒤이은 실측이 "직전에 커밋된 요청"까지
     * 반영한 최신값이 된다(스냅샷 한도의 TOCTOU 차단).
     */
    private long lockPeriodRow(UserPlan userPlan, LocalDate period, Long companyId) {
        ensurePeriodRow(userPlan, period, companyId);
        return usageCounterRepository.lockPeriodRow(userPlan.getId(), period)
                // UPSERT 직후라 사실상 도달 불가 — 도달했다면 집계 행이 외부에서 삭제된 것이므로,
                // 한도 초과(403)로 오인시키지 않고 서버 오류로 표면화한다.
                .orElseThrow(() -> {
                    log.error("사용량 집계 행 잠금 실패 — userPlanId={} period={}", userPlan.getId(), period);
                    return new BusinessException(ErrorCode.INTERNAL_ERROR);
                });
    }

    /**
     * 당월 집계 행이 없으면 실측 스냅샷으로 시드해 만든다. 월이 바뀔 때마다 새 행이 실측값으로 다시
     * 시드되므로, 스냅샷 카운터는 월 경계에서도 0으로 리셋되지 않고 자동으로 정합을 회복한다.
     */
    private void ensurePeriodRow(UserPlan userPlan, LocalDate period, Long companyId) {
        if (usageCounterRepository.existsByUserPlanIdAndPeriod(userPlan.getId(), period)) {
            return;
        }
        usageCounterRepository.insertPeriodRowIfAbsent(
                userPlan.getId(), period, measureFacilities(companyId), measureSeats(companyId));
    }

    private int measureFacilities(Long companyId) {
        // 시설물은 회사 소유 자원이다(V11 이후) — 개인 구독에는 귀속 시설물이 없다.
        return companyId == null ? 0 : (int) facilityRepository.countByCompanyId(companyId);
    }

    private int measureSeats(Long companyId) {
        // 개인 구독은 본인 1석 고정(MembershipService#getSeats 와 동일 관례).
        return companyId == null
                ? 1
                : (int) userRepository.countByCompanyIdAndStatus(companyId, UserStatus.ACTIVE);
    }

    /**
     * "현재 구독" 판정 — {@code MembershipService#resolveCurrentUserPlan} 과 동일 기준(회사면 company_id,
     * 아니면 user_id / ACTIVE 우선, 없으면 UPGRADE_REQUESTED)을 재사용한다. 화면 숫자와 한도가 다른
     * 구독을 보면 서로 따로 놀기 때문이다.
     *
     * <p>⚠️ companyId 는 반드시 호출부가 {@code CompanyScopeGuard.requireEffectiveMembership} 등으로
     * 재검증한 값이어야 한다 — {@code LoginUser.companyId} 는 로그인 시점 스냅샷이라 그대로 쓰면 엉뚱한
     * 회사에 사용량을 적립할 수 있다.
     *
     * <p>활성 구독이 없으면 <b>무제한 통과시키지 않는다</b>(한도 우회 방지). 대신 FREE 구독을 독립
     * 트랜잭션으로 자가 프로비저닝하고 재조회한다 — 모든 계정은 FREE 를 기본으로 갖는 것이 계약(#517)이라,
     * 데이터 누락으로 구독이 없는 계정을 무제한도 전면 차단도 아닌 "FREE 한도 적용"으로 수렴시킨다.
     * 그래도 구독을 만들지 못하면(요금제 시드 누락 등) 최종적으로 차단한다(fail-closed).
     */
    private UserPlan resolveLivePlan(Long userId, Long companyId) {
        Optional<UserPlan> found = findLivePlan(userId, companyId);
        if (found.isPresent()) {
            return found.get();
        }
        try {
            planProvisioningService.provisionFreePlanInNewTransaction(userId, companyId);
        } catch (RuntimeException e) {
            // 동시 프로비저닝 경합(부분 UQ 위반) 등 — 승자가 만든 구독을 아래 재조회에서 그대로 쓴다.
            log.warn("FREE 구독 자가 프로비저닝 실패 — userId={} companyId={}", userId, companyId, e);
        }
        return findLivePlan(userId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
    }

    private Optional<UserPlan> findLivePlan(Long userId, Long companyId) {
        if (companyId != null) {
            return userPlanRepository
                    .findFirstByCompanyIdAndStatusOrderByStartedAtDesc(companyId, UserPlanStatus.ACTIVE)
                    .or(() -> userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(
                            companyId, UserPlanStatus.UPGRADE_REQUESTED));
        }
        if (userId == null) {
            return Optional.empty();
        }
        return userPlanRepository
                .findFirstByUserIdAndStatusOrderByStartedAtDesc(userId, UserPlanStatus.ACTIVE)
                .or(() -> userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
                        userId, UserPlanStatus.UPGRADE_REQUESTED));
    }

    private Plan findPlan(Long planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
    }

    private LocalDate currentPeriod() {
        return YearMonth.now(KST).atDay(1);
    }
}
