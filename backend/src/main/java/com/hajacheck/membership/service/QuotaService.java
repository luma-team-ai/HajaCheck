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
import java.time.Clock;
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
    // 미결제 유예(#1177) 판정 — 유예 중이면 한도를 FREE 로 낮춘다(resolveEffectivePlan).
    private final PaymentGraceService paymentGraceService;
    private final FacilityRepository facilityRepository;
    private final UserRepository userRepository;
    // SchedulingConfig 가 KST 로 고정해 제공하는 빈 — 서버 기본 타임존에 흔들리지 않게 하고,
    // 월 경계(말일 차감 → 익월 보상) 같은 시점 의존 동작을 테스트가 결정적으로 재현할 수 있게 한다.
    private final Clock clock;

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
        Plan plan = resolveEffectivePlan(userPlan);
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
        Plan plan = resolveEffectivePlan(userPlan);
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
     * 좌석 여유 조회(읽기 전용) — 초대 코드 발급 시점 선검사(#857)용. {@link #reserveSeat}과 달리 잠금·차감이
     * 없어 상태를 바꾸지 않으므로 읽기 전용 트랜잭션에서도 호출할 수 있다.
     *
     * <p>판정 기준은 {@link #reserveSeat}와 동일한 소스({@link #resolveLivePlan}·{@link #measureSeats})를
     * 그대로 재사용한다 — 발급 판정과 redeem 판정이 서로 다른 기준을 보면 "발급은 됐는데 redeem은 막힘"이
     * 재발한다.
     *
     * <p>⚠️ advisory(조언적) 판정이다. 이 호출과 실제 활성화({@link #reserveSeat}) 사이에 다른 초대가
     * 먼저 redeem 돼 좌석이 찰 수 있으므로(경합), true를 반환해도 이후 {@link #reserveSeat}가 거부할 수
     * 있다. 최종 방어선은 여전히 {@link #reserveSeat}의 원자적 조건부 UPDATE다 — 이 메서드는 그것을
     * 대체하지 않는다.
     */
    public boolean hasAvailableSeat(Long companyId) {
        UserPlan userPlan = resolveLivePlan(null, companyId);
        Plan plan = resolveEffectivePlan(userPlan);
        Integer maxSeats = plan.getMaxSeats();
        if (maxSeats == null) {
            // null = 무제한(Plan javadoc, reserveSeat과 동일 관례).
            return true;
        }
        return measureSeats(companyId) < maxSeats;
    }

    /**
     * 이 시설물이 현재 요금제 한도를 넘어 <b>읽기 전용</b>인지(#890) — 회사 시설물을 id 오름차순으로 봤을 때
     * 순위가 {@code plans.max_facilities} 를 넘으면 읽기 전용이다.
     *
     * <p><b>상태 컬럼을 두지 않고 계산으로 판정하는 이유</b>: 한도가 다시 올라가면(업그레이드) 별도 복구
     * 작업 없이 <b>자동으로 다시 쓰기 가능</b>해진다. 컬럼 방식은 하향·상향 양쪽에서 동기화를 유지해야 하고
     * 어긋나면 영구 드리프트가 된다.
     *
     * <p>읽기 전용이어도 <b>조회·기존 점검 이력은 그대로</b>다. 차단 대상은 <b>신규 점검 생성</b>뿐 —
     * 점검·보고서가 시설물을 참조하므로 목록에서 빼거나 삭제하면 참조 정합성이 깨진다(#890 정책).
     *
     * <p>활성 구독이 없으면(개인 계정 등) 판정 대상이 아니므로 false 다 — 회사 자원이 아닌 것에 회사 한도를
     * 적용하지 않는다.
     */
    public boolean isFacilityReadOnly(Long companyId, Long facilityId) {
        if (companyId == null || facilityId == null) {
            return false;
        }
        Integer maxFacilities = findLivePlan(null, companyId)
                .map(userPlan -> resolveEffectivePlan(userPlan).getMaxFacilities())
                .orElse(null);
        if (maxFacilities == null) {
            // 무제한이거나 활성 구독 없음 — 어느 쪽이든 읽기 전용으로 떨어지는 시설물이 없다.
            return false;
        }
        return facilityRepository.countByCompanyIdAndIdLessThanEqual(companyId, facilityId) > maxFacilities;
    }

    /**
     * 월 분석 한도 차감(요청 1건 = 이미지 {@code imageCount} 장).
     *
     * <p>호출부(분석 시작)는 트랜잭션 밖이라 이 메서드가 독립 트랜잭션으로 커밋된다 — 이후 단계가 실패하면
     * 호출부가 반드시 {@link #refundAnalysisQuota} 로 보상해야 한다.
     *
     * @return 실제로 갱신한 좌표({@link AnalysisQuotaCharge}) — 호출부는 이 값을 그대로 보상에 넘긴다.
     *         보상이 구독·기간을 다시 계산하지 않아야 하는 이유는 그 record 의 javadoc 참고.
     */
    @Transactional
    public AnalysisQuotaCharge consumeAnalysisQuota(Long userId, Long companyId, int imageCount) {
        if (imageCount <= 0) {
            return AnalysisQuotaCharge.none();
        }
        UserPlan userPlan = resolveLivePlan(userId, companyId);
        Plan plan = resolveEffectivePlan(userPlan);
        LocalDate period = currentPeriod();
        ensurePeriodRow(userPlan, period, companyId);

        Integer maxImages = plan.getMaxMonthlyAnalyses();
        int updated = maxImages == null
                ? usageCounterRepository.consumeAnalysisQuotaUnlimited(userPlan.getId(), period, imageCount)
                : usageCounterRepository.consumeAnalysisQuota(userPlan.getId(), period, imageCount, maxImages);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.PLAN_ANALYSIS_QUOTA_EXCEEDED);
        }
        return AnalysisQuotaCharge.of(userPlan.getId(), period, imageCount);
    }

    /**
     * 분석 시작·실행이 실패했을 때의 보상 차감 — {@link #consumeAnalysisQuota} 가 돌려준 좌표
     * ({@link AnalysisQuotaCharge})가 가리키는 <b>그 행만</b> 되돌린다.
     *
     * <p>⚠️ 구독·기간을 <b>다시 조회하거나 계산하지 않는다</b>(머신 검수 P2/P3-1). 분석 워커는 {@code @Async}
     * 로 수 분을 돌기 때문에, 보상 시점에 재계산하면 월이 넘어갔을 때 엉뚱한 기간 행을, 요금제가 바뀌었을 때
     * 엉뚱한 구독 행을 감산한다. 근거와 실패 시나리오는 {@link AnalysisQuotaCharge} javadoc 참고.
     *
     * <p>{@code REQUIRES_NEW} 인 이유: 호출부는 대부분 "원래 실패"를 처리하는 예외 경로다. 보상을 호출부
     * 트랜잭션에 태우면 보상 쪽 실패가 호출부 트랜잭션까지 롤백 전용으로 오염시킨다.
     *
     * <p>⚠️ 그 대가로 <b>이 메서드는 반드시 트랜잭션 밖에서 호출해야 한다</b>(되돌릴 차감이 이미 커밋돼
     * 있어야 한다). 실제 두 호출부는 이 전제를 만족한다: {@code InspectionAnalysisService#startAnalysis}
     * 와 {@code InspectionAnalysisWorker#runAsync} 는 둘 다 트랜잭션 밖이라 {@link #consumeAnalysisQuota}
     * 가 자기 트랜잭션으로 즉시 커밋된 뒤에 실패 경로로 들어간다. (좌표 전파로 대상 행 <b>지정</b> 방식이
     * 바뀌었을 뿐, 이 트랜잭션 계약은 그대로다 — 좌표가 정확해도 그 행이 아직 커밋 전이면 못 되돌린다.)
     *
     * <p>⚠️ <b>전제를 어기면 조용한 no-op 이 아니라 요청이 멈춘다</b>(재검토 P2 — 이전 주석의 "0행 갱신"
     * 서술은 틀렸다). 호출부 트랜잭션 TX-A 가 {@link #consumeAnalysisQuota}(REQUIRED, TX-A 에 조인)로
     * 같은 {@code usage_counters} 행을 이미 갱신해 <b>배타 잠금을 쥔 상태</b>라면, {@code REQUIRES_NEW}
     * 로 열린 TX-B 의 UPDATE 는 그 잠금을 기다린다. 그런데 TX-A 는 이 메서드 호출이 반환되기를 기다리므로
     * 영원히 커밋되지 않는다 — DB 교착이 아니라 JVM 대기라 <b>PostgreSQL 교착 탐지기가 발동하지 않고</b>,
     * {@code lock_timeout}/{@code statement_timeout} 도 설정돼 있지 않아 커넥션 2개를 쥔 채 무기한 멈춘다.
     * "0행 갱신으로 끝난다"가 성립하는 건 호출부가 <b>차감을 하지 않은</b> 트랜잭션일 때뿐이다.
     *
     * <p>⚠️ 이 메서드는 예외를 <b>삼키지 않는다</b>. 예전에는 본문을 try/catch 로 감쌌지만, 그러면
     * 프록시의 커밋 단계에서 새로 던져지는 {@code UnexpectedRollbackException} 은 못 잡아 원래 원인
     * ({@code ANALYSIS_QUEUE_FULL} 등)을 500 으로 덮어쓴다(리뷰 P2). 대신 <b>호출부가</b>
     * {@code catch (RuntimeException)} 로 감싸 경고만 남기고 원래 예외를 그대로 전파해야 한다 —
     * 보상 실패의 최악 결과는 사용량이 조금 과대 집계되는 것뿐이라 원인을 가릴 이유가 없다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refundAnalysisQuota(AnalysisQuotaCharge charge) {
        if (charge == null || !charge.isCharged()) {
            return;
        }
        usageCounterRepository.refundAnalysisQuota(
                charge.userPlanId(), charge.period(), charge.imageCount());
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

    /**
     * 이 구독에 <b>실제로 적용할</b> 요금제 — 미결제 유예 중이면 FREE, 아니면 구독 요금제 그대로(#1177).
     *
     * <p><b>왜 필요한가</b>: 유료→유료 하향(C안)은 적용 시점이 무인 배치라 결제를 받을 수 없어 대상
     * 요금제를 <b>결제 없이</b> 발급하고 유예 안에 결제받는다. 그 유예 동안 대상 요금제의 한도를 그대로
     * 주면 <b>예약을 반복하는 것만으로 유예기간만큼 상위 한도를 무료로</b> 쓸 수 있다 — #1105 보안 P1
     * (무결제 유료 발급)과 같은 부류의 우회로다. 유예 중 FREE 한도를 적용해 "무결제로 얻는 유료 혜택 0"을
     * 만든다.
     *
     * <p><b>⚠️ 이 클래스가 요금제를 해석하는 모든 지점이 이 메서드를 거쳐야 한다</b> — 한 곳이라도
     * {@code findPlan(userPlan.getPlanId())} 로 남으면 그 자원(시설물·좌석·월 분석)만 유예 중에도 유료
     * 한도로 열려 구멍이 된다. 이 클래스를 <b>거치지 않는</b> 엔타이틀먼트(상담사 연결·AI 부가기능)는
     * 각 서비스가 같은 방식으로 {@code PaymentGraceService#resolveEffectivePlan} 을 경유한다.
     *
     * <p>비용: 판정은 이미 로딩된 {@code user_plans.payment_pending_until} 한 컬럼이라 <b>추가 조회가
     * 없다</b>(유예 중일 때만 FREE 요금제를 한 번 더 읽는다).
     */
    private Plan resolveEffectivePlan(UserPlan userPlan) {
        return paymentGraceService.resolveEffectivePlan(userPlan, findPlan(userPlan.getPlanId()));
    }

    private LocalDate currentPeriod() {
        // clock 은 SchedulingConfig 에서 Clock.system(Asia/Seoul) 로 제공된다 — KST 상수는 그 계약을
        // 문서화하고, 혹시 다른 존의 Clock 이 주입되더라도 집계 기간만은 KST 로 고정되게 한다.
        return YearMonth.now(clock.withZone(KST)).atDay(1);
    }
}
