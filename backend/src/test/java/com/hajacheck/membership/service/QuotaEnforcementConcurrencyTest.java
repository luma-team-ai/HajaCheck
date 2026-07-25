package com.hajacheck.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.facility.dto.FacilityCreateRequest;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.facility.service.FacilityService;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.invitecode.service.InviteCodeService;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 플랜 한도 강제(#843 / HAJA-441)의 <b>동시 요청 경합</b> 검증 — 같은 회사로 한도보다 많은 요청을 동시에
 * 던져도 정확히 한도만큼만 성공하는지(한도 누수 0) 실 PostgreSQL(Testcontainers)로 확인한다.
 *
 * <p>⚠️ {@code InspectionServiceConcurrencyTest}/{@code PlatformAdminUserServiceConcurrencyTest}와 같은
 * 이유로 클래스 레벨 {@code @Transactional}을 붙이지 않는다 — 각 워커 스레드가 서비스 프록시를 통해
 * 독립된 실 트랜잭션을 얻어야 진짜 경합이 재현된다(공용 트랜잭션이 있으면 한 커넥션을 공유해 무의미).
 * 커밋된 데이터는 {@link #tearDown()}에서 직접 정리한다.
 *
 * <p>한도값은 하드코딩하지 않고 시드된 {@code plans} 행에서 읽어 기대치를 유도한다(시드가 바뀌어도
 * "정확히 한도만큼만 성공한다"는 계약은 그대로 검증된다). 다만 워커 스레드는 각자 커넥션을 붙잡은 채
 * 행 잠금을 기다리므로, HikariCP 기본 풀(10)을 넘지 않도록 스레드 수를 8 이하로 잡는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class QuotaEnforcementConcurrencyTest extends PostgresTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MAX_WORKER_THREADS = 8;

    @Autowired
    private FacilityService facilityService;
    @Autowired
    private InviteCodeService inviteCodeService;
    @Autowired
    private QuotaService quotaService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private CompanyMembershipRepository companyMembershipRepository;
    @Autowired
    private FacilityRepository facilityRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private UserPlanRepository userPlanRepository;
    @Autowired
    private UsageCounterRepository usageCounterRepository;

    private Long ownerId;
    private Long companyId;
    private Long ownerMembershipId;
    private Long userPlanId;
    private final List<Long> extraUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(User.builder()
                .email("quota-owner-" + System.nanoTime() + "@haja.com")
                .name("한도테스트대표")
                .role(Role.ADMIN)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build());

        // business_registration_number 는 varchar(20) — 접두사 짧게 + nanoTime 뒷자리로 유니크성 확보.
        String brn = "qbrn-" + (System.nanoTime() % 10_000_000_000L);
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)한도테스트", brn,
                "김대표", "서울시 강남구", null, "http://files/brn.png", "{}"));
        company.markBusinessVerified();
        company.approve(owner.getId());
        company = companyRepository.save(company);
        owner.assignToCompany(company.getId());
        userRepository.save(owner);

        CompanyMembership ownerMembership = companyMembershipRepository.save(
                CompanyMembership.approvedOwner(company.getId(), owner.getId()));

        this.ownerId = owner.getId();
        this.companyId = company.getId();
        this.ownerMembershipId = ownerMembership.getId();
    }

    @AfterEach
    void tearDown() {
        if (userPlanId != null) {
            usageCounterRepository.findByUserPlanIdAndPeriod(userPlanId, currentPeriod())
                    .ifPresent(usageCounterRepository::delete);
            userPlanRepository.deleteById(userPlanId);
            userPlanId = null;
        }
        facilityRepository.findByCompanyIdOrderByIdAsc(companyId, PageRequest.of(0, 500))
                .forEach(facilityRepository::delete);
        companyMembershipRepository.deleteById(ownerMembershipId);

        // circular FK(companies.owner_user_id ↔ users.company_id) — company_id 를 먼저 끊는다.
        for (Long userId : extraUserIds) {
            userRepository.findById(userId).ifPresent(u -> {
                u.assignToCompany(null);
                userRepository.save(u);
            });
        }
        User owner = userRepository.findById(ownerId).orElseThrow();
        owner.assignToCompany(null);
        userRepository.save(owner);

        companyRepository.deleteById(companyId);
        extraUserIds.forEach(userRepository::deleteById);
        extraUserIds.clear();
        userRepository.deleteById(ownerId);
    }

    @Test
    void 시설물_동시등록요청이_한도를_넘어도_정확히_한도만큼만_성공한다() throws Exception {
        // FREE(max_facilities=1) — 한도가 작아 워커 스레드를 커넥션 풀 안에서 넉넉히 잡을 수 있다.
        Plan plan = givenCompanyPlan(PlanName.FREE);
        int limit = requireLimit(plan.getMaxFacilities());
        int threadCount = Math.min(limit + 4, MAX_WORKER_THREADS);
        assertThat(threadCount).isGreaterThan(limit);

        List<Boolean> results = runConcurrently(threadCount, index -> {
            facilityService.create(ownerId, companyId, new FacilityCreateRequest(
                    "동시등록시설" + index, "BUILDING", null, null, null,
                    null, null, null, null, null, null, null));
            return true;
        }, ErrorCode.PLAN_FACILITY_QUOTA_EXCEEDED);

        // 잠금+원자적 조건부 UPDATE 가 없었다면 모든 스레드가 같은 실측값(0)을 읽고 전부 통과했을 것.
        assertThat(succeeded(results)).isEqualTo(limit);
        assertThat(facilityRepository.countByCompanyId(companyId)).isEqualTo(limit);
        assertThat(currentUsage().getFacilityCount()).isEqualTo(limit);
    }

    @Test
    void 월분석_동시차감요청이_한도를_넘어도_정확히_한도만큼만_성공한다() throws Exception {
        Plan plan = givenCompanyPlan(PlanName.FREE);
        int limit = requireLimit(plan.getMaxMonthlyAnalyses());
        // 요청 1건 = 이미지 N장이므로 한도를 5등분한 장수를 한 요청 단위로 삼는다 → 정확히 5건만 통과해야 한다.
        int imagesPerRequest = Math.max(1, limit / 5);
        int expectedSuccess = limit / imagesPerRequest;
        int threadCount = Math.min(expectedSuccess + 3, MAX_WORKER_THREADS);
        assertThat(threadCount).isGreaterThan(expectedSuccess);

        List<Boolean> results = runConcurrently(threadCount, index -> {
            quotaService.consumeAnalysisQuota(ownerId, companyId, imagesPerRequest);
            return true;
        }, ErrorCode.PLAN_ANALYSIS_QUOTA_EXCEEDED);

        assertThat(succeeded(results)).isEqualTo(expectedSuccess);
        UsageCounter usage = currentUsage();
        assertThat(usage.getAnalyzedImageCount()).isEqualTo(expectedSuccess * imagesPerRequest);
        assertThat(usage.getAnalyzedImageCount()).isLessThanOrEqualTo(limit);
        assertThat(usage.getAnalysisRequestCount()).isEqualTo(expectedSuccess);
    }

    @Test
    void 좌석_동시배정요청이_한도를_넘어도_정확히_남은좌석만큼만_성공한다() throws Exception {
        // STANDARD(max_seats=3) — FREE(1석)는 대표가 이미 다 쓰고 있어 "남은 좌석만큼 성공"을 관찰할 수 없다.
        Plan plan = givenCompanyPlan(PlanName.STANDARD);
        int limit = requireLimit(plan.getMaxSeats());
        int remainingSeats = limit - 1; // 대표(owner)가 1석 사용 중
        int threadCount = Math.min(remainingSeats + 3, MAX_WORKER_THREADS);
        assertThat(threadCount).isGreaterThan(remainingSeats);

        List<Long> waitingUserIds = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            User waiting = userRepository.save(User.createSocialUser(
                    SocialProvider.KAKAO, "quota-social-" + System.nanoTime() + "-" + i,
                    "quota-waiting-" + System.nanoTime() + "-" + i + "@haja.com", "대기자" + i));
            waitingUserIds.add(waiting.getId());
            extraUserIds.add(waiting.getId());
        }
        String code = inviteCodeService.issue(companyId).code();

        List<Boolean> results = runConcurrently(threadCount,
                index -> {
                    inviteCodeService.redeem(code, waitingUserIds.get(index));
                    return true;
                },
                ErrorCode.PLAN_SEAT_QUOTA_EXCEEDED);

        assertThat(succeeded(results)).isEqualTo(remainingSeats);
        assertThat(userRepository.countByCompanyIdAndStatus(companyId, UserStatus.ACTIVE)).isEqualTo(limit);
        assertThat(currentUsage().getSeatCount()).isEqualTo(limit);
    }

    @Test
    void 활성구독이_없으면_무제한통과가_아니라_FREE구독을_자가프로비저닝해_FREE한도를_적용한다() {
        // 사전조건: 이 회사에는 user_plans 행이 아예 없다(PLAN_NOT_FOUND 상황).
        assertThat(userPlanRepository.existsByCompanyIdAndStatusIn(
                companyId, List.of(UserPlanStatus.ACTIVE, UserPlanStatus.UPGRADE_REQUESTED))).isFalse();
        int freeLimit = requireLimit(planRepository.findByName(PlanName.FREE).orElseThrow().getMaxFacilities());

        for (int i = 0; i < freeLimit; i++) {
            facilityService.create(ownerId, companyId, new FacilityCreateRequest(
                    "자가치유시설" + i, "BUILDING", null, null, null,
                    null, null, null, null, null, null, null));
        }

        UserPlan provisioned = userPlanRepository
                .findFirstByCompanyIdAndStatusOrderByStartedAtDesc(companyId, UserPlanStatus.ACTIVE)
                .orElseThrow();
        this.userPlanId = provisioned.getId();
        assertThat(provisioned.getPlanId())
                .isEqualTo(planRepository.findByName(PlanName.FREE).orElseThrow().getId());

        // 무제한 통과였다면 여기서도 등록이 성공했을 것 — FREE 한도가 실제로 걸린다.
        assertThatThrownBy(() -> facilityService.create(ownerId, companyId, new FacilityCreateRequest(
                "한도초과시설", "BUILDING", null, null, null,
                null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_FACILITY_QUOTA_EXCEEDED));
    }

    private Plan givenCompanyPlan(PlanName planName) {
        Plan plan = planRepository.findByName(planName).orElseThrow();
        this.userPlanId = userPlanRepository.save(UserPlan.forCompany(companyId, plan.getId())).getId();
        return plan;
    }

    private static int requireLimit(Integer limit) {
        assertThat(limit).as("무제한(null) 요금제로는 한도 경합을 검증할 수 없다").isNotNull();
        return limit;
    }

    private static long succeeded(List<Boolean> results) {
        return results.stream().filter(Boolean::booleanValue).count();
    }

    /**
     * 워커 스레드를 동시에 출발시키고, 한도 초과({@code expectedRejection})만 실패로 집계한다.
     * 그 밖의 예외는 그대로 전파시켜 테스트가 조용히 통과하지 않게 한다.
     */
    private List<Boolean> runConcurrently(int threadCount, IndexedAction action, ErrorCode expectedRejection)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return action.run(index);
                } catch (BusinessException e) {
                    if (e.getErrorCode() != expectedRejection) {
                        throw e;
                    }
                    return false;
                }
            }));
        }
        ready.await();
        start.countDown();

        List<Boolean> results = new ArrayList<>();
        for (Future<Boolean> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        executor.shutdown();
        return results;
    }

    private UsageCounter currentUsage() {
        return usageCounterRepository.findByUserPlanIdAndPeriod(userPlanId, currentPeriod()).orElseThrow();
    }

    private static LocalDate currentPeriod() {
        return YearMonth.now(KST).atDay(1);
    }

    @FunctionalInterface
    private interface IndexedAction {
        boolean run(int index) throws Exception;
    }
}
