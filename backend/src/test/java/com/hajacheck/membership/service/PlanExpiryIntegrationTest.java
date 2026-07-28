package com.hajacheck.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.membership.config.PlanExpiryProperties;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.membership.scheduler.PlanExpiryScheduler;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 구독 결제 주기 만료 → FREE 자동 강등(#1145 / HAJA-549)의 <b>실제 전이</b>를 실 PostgreSQL
 * (Testcontainers)로 검증한다 — 만료/신규 발급·결제 주기 NULL화·사용량 이월·초과 좌석 정지·멱등성·
 * 대상 조회 경계·PLAN_EXPIRED 알림(PG enum 라벨 포함)까지.
 *
 * <p>⚠️ 클래스 레벨 {@code @Transactional} 을 붙이지 않는다 — {@link PlanExpiryWriter} 가
 * {@code REQUIRES_NEW} 로 독립 트랜잭션을 열기 때문에, 테스트가 트랜잭션을 열고 있으면 픽스처가
 * 아직 커밋되지 않아 writer 쪽에서 보이지 않는다({@code QuotaEnforcementConcurrencyTest} 와 동일한
 * 이유). 커밋된 데이터는 {@link #tearDown()} 에서 직접 정리한다.
 *
 * <p>스케줄러의 통제 로직(기동 스위치·1회 상한·건별 장애 격리·keyset 순회)은 목 기반
 * {@code PlanExpirySchedulerTest} 가 담당한다 — 여기서 상한을 다시 검증하지 않는 이유는, 이 테스트가
 * 공유 컨테이너의 <b>전역 대상 건수</b>에 의존하게 되어 다른 테스트가 남긴 행에 흔들리기 때문이다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PlanExpiryIntegrationTest extends PostgresTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 2026-07-28T00:30 KST = 2026-07-27T15:30Z — KST 00:00~09:00(UTC 로는 전날) 구간. */
    private static final Instant KST_DAWN = Instant.parse("2026-07-27T15:30:00Z");

    @Autowired
    private PlanExpiryWriter planExpiryWriter;
    @Autowired
    private PlanExpiryScheduler planExpiryScheduler;
    @Autowired
    private PlanExpiryProperties planExpiryProperties;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private CompanyMembershipRepository companyMembershipRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private UserPlanRepository userPlanRepository;
    @Autowired
    private UsageCounterRepository usageCounterRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdUserPlanIds = new ArrayList<>();
    private final List<Long> createdMembershipIds = new ArrayList<>();
    private final List<Long> createdCompanyIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private boolean originalEnabled;
    private int originalMaxPerRun;

    @BeforeEach
    void captureProperties() {
        // PlanExpiryProperties 는 싱글턴 빈이라 테스트가 바꾼 값이 다음 테스트로 새지 않게 원복한다.
        originalEnabled = planExpiryProperties.isEnabled();
        originalMaxPerRun = planExpiryProperties.getMaxPerRun();
    }

    @AfterEach
    void tearDown() {
        planExpiryProperties.setEnabled(originalEnabled);
        planExpiryProperties.setMaxPerRun(originalMaxPerRun);

        createdUserIds.forEach(userId -> notificationRepository
                .findAllByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 100))
                .forEach(notificationRepository::delete));
        // 강등으로 새로 발급된 FREE 구독까지 포함해 이 테스트가 만든 소유 주체의 구독을 전부 지운다.
        List<Long> planIdsToDelete = new ArrayList<>(createdUserPlanIds);
        for (Long companyId : createdCompanyIds) {
            userPlanRepository.findByCompanyIdIsNotNull().stream()
                    .filter(up -> companyId.equals(up.getCompanyId()))
                    .map(UserPlan::getId)
                    .forEach(planIdsToDelete::add);
        }
        planIdsToDelete.stream().distinct().forEach(userPlanId -> {
            usageCounterRepository.findByUserPlanIdAndPeriod(userPlanId, currentPeriod())
                    .ifPresent(usageCounterRepository::delete);
            userPlanRepository.findById(userPlanId).ifPresent(userPlanRepository::delete);
        });
        createdMembershipIds.forEach(companyMembershipRepository::deleteById);

        // circular FK(companies.owner_user_id ↔ users.company_id) — company_id 를 먼저 끊는다.
        createdUserIds.forEach(userId -> userRepository.findById(userId).ifPresent(user -> {
            user.assignToCompany(null);
            userRepository.save(user);
        }));
        createdCompanyIds.forEach(companyRepository::deleteById);
        createdUserIds.forEach(userRepository::deleteById);

        createdUserPlanIds.clear();
        createdMembershipIds.clear();
        createdCompanyIds.clear();
        createdUserIds.clear();
    }

    private LocalDate currentPeriod() {
        return YearMonth.now(KST).atDay(1);
    }

    private User newUser(String prefix, Role role) {
        User user = userRepository.save(User.builder()
                .email(prefix + "-" + System.nanoTime() + "@haja.test")
                .name(prefix)
                .role(role)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build());
        createdUserIds.add(user.getId());
        return user;
    }

    /** 승인 완료된 회사 + owner(ADMIN) 를 만든다. */
    private Company newApprovedCompany(User owner) {
        String brn = "ebrn-" + (System.nanoTime() % 10_000_000_000L);
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)만료테스트", brn,
                "김대표", "서울시 강남구", null, "http://files/brn.png", "{}"));
        company.markBusinessVerified();
        company.approve(owner.getId());
        company = companyRepository.save(company);
        createdCompanyIds.add(company.getId());

        owner.assignToCompany(company.getId());
        userRepository.save(owner);
        createdMembershipIds.add(companyMembershipRepository
                .save(CompanyMembership.approvedOwner(company.getId(), owner.getId()))
                .getId());
        return company;
    }

    private User newCompanyMember(Long companyId) {
        User member = newUser("만료테스트구성원", Role.USER);
        member.assignToCompany(companyId);
        return userRepository.save(member);
    }

    private Plan plan(PlanName name) {
        return planRepository.findByName(name).orElseThrow();
    }

    private UserPlan newCompanyPlan(Long companyId, PlanName planName, Instant periodStart, Instant periodEnd) {
        UserPlan userPlan = userPlanRepository.saveAndFlush(
                UserPlan.forCompany(companyId, plan(planName).getId()));
        createdUserPlanIds.add(userPlan.getId());
        setBillingPeriod(userPlan.getId(), periodStart, periodEnd);
        return userPlanRepository.findById(userPlan.getId()).orElseThrow();
    }

    private UserPlan newPersonalPlan(Long userId, PlanName planName, Instant periodStart, Instant periodEnd) {
        UserPlan userPlan = userPlanRepository.saveAndFlush(
                UserPlan.forUser(userId, plan(planName).getId()));
        createdUserPlanIds.add(userPlan.getId());
        setBillingPeriod(userPlan.getId(), periodStart, periodEnd);
        return userPlanRepository.findById(userPlan.getId()).orElseThrow();
    }

    /**
     * 결제 주기를 원하는 값으로 직접 박는다. 엔티티에는 {@code startNewBillingPeriod}(시작+1개월) /
     * {@code carryOverBillingPeriod}(승계) 만 있어 임의 시각을 지정할 수 없는데, 경계 테스트는
     * "기준시각과 정확히 같은 순간"을 만들어야 하므로 SQL 로 세팅한다(운영 코드 경로는 그대로 둔다).
     */
    private void setBillingPeriod(Long userPlanId, Instant periodStart, Instant periodEnd) {
        jdbcTemplate.update(
                "update user_plans set current_period_start = ?, current_period_end = ? where id = ?",
                periodStart == null ? null : java.sql.Timestamp.from(periodStart),
                periodEnd == null ? null : java.sql.Timestamp.from(periodEnd),
                userPlanId);
        // JPA 1차 캐시에 남은 옛 스냅샷이 이후 조회에 섞이지 않게 비운다.
        userPlanRepository.flush();
    }

    private List<Long> expiryTargetIds(Instant threshold) {
        return userPlanRepository.findExpiryTargetIds(
                UserPlanStatus.ACTIVE, threshold, 0L, PageRequest.of(0, 500));
    }

    private UserPlan activeCompanyPlan(Long companyId) {
        return userPlanRepository
                .findFirstByCompanyIdAndStatusOrderByStartedAtDesc(companyId, UserPlanStatus.ACTIVE)
                .orElseThrow();
    }

    private long activeCompanyPlanCount(Long companyId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from user_plans where company_id = ? and status = 'ACTIVE'",
                Long.class, companyId);
    }

    private List<Notification> planExpiredNotifications(Long userId) {
        return notificationRepository
                .findAllByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 100))
                .stream()
                .filter(n -> n.getType() == NotificationType.PLAN_EXPIRED)
                .toList();
    }

    @Test
    @DisplayName("만료된 회사 유료 구독은 FREE로 강등되고 결제주기는 NULL(무기한)이 되며 사용량이 이월되고 초과 좌석이 정지된다")
    void 회사_유료구독_만료강등() {
        User owner = newUser("만료테스트대표", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        User member1 = newCompanyMember(company.getId());
        User member2 = newCompanyMember(company.getId());

        Instant now = Instant.now();
        Instant periodStart = now.minusSeconds(31 * 24 * 3600L);
        Instant periodEnd = now.minusSeconds(3600);
        // STANDARD(3석) → FREE(1석)이라 owner 를 제외한 2명이 정지 대상이 된다.
        UserPlan expiring = newCompanyPlan(company.getId(), PlanName.STANDARD, periodStart, periodEnd);
        usageCounterRepository.saveAndFlush(UsageCounter.create(
                expiring.getId(), currentPeriod(), 7, 2, 3, 3, 1, 0));

        PlanExpiryResult result = planExpiryWriter.expireToFreePlan(expiring.getId(), now);

        assertThat(result.downgraded()).isTrue();
        assertThat(result.previousPlanName()).isEqualTo(PlanName.STANDARD);
        assertThat(result.recipientUserId())
                .as("회사 구독의 알림 수신자는 회사 owner 다")
                .isEqualTo(owner.getId());
        assertThat(result.suspendedSeatCount()).isEqualTo(2);

        UserPlan previous = userPlanRepository.findById(expiring.getId()).orElseThrow();
        assertThat(previous.getStatus()).isEqualTo(UserPlanStatus.EXPIRED);
        assertThat(previous.getEndedAt()).isNotNull();

        UserPlan renewed = activeCompanyPlan(company.getId());
        assertThat(renewed.getId()).isNotEqualTo(expiring.getId());
        assertThat(renewed.getPlanId()).isEqualTo(plan(PlanName.FREE).getId());
        assertThat(renewed.getCurrentPeriodEnd())
                .as("FREE 는 무기한이다 — 여기에 값이 남으면 한 달 뒤 다시 강등 대상이 되는 무한 루프가 된다")
                .isNull();
        assertThat(renewed.getCurrentPeriodStart())
                .as("결제가 없었으므로 주기 시작은 승계한다(#1104 carryOverBillingPeriod 규칙)")
                .isEqualTo(periodStart);

        // 사용량 이월(#851) — 이월하지 않으면 강등이 곧 월 분석 한도 리셋이 되어 한도 강제(#843)가
        // 무력화된다(이전 행은 이력으로 남기고 새 구독에 같은 값을 복사하는 방식).
        UsageCounter carried = usageCounterRepository
                .findByUserPlanIdAndPeriod(renewed.getId(), currentPeriod()).orElseThrow();
        assertThat(carried.getAnalyzedImageCount()).isEqualTo(7);
        assertThat(carried.getAnalysisRequestCount()).isEqualTo(3);

        assertThat(userRepository.findById(member1.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.SUSPENDED);
        assertThat(userRepository.findById(member2.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.SUSPENDED);
        assertThat(userRepository.findById(owner.getId()).orElseThrow().getStatus())
                .as("owner 가 정지되면 회사가 관리 불능이 된다 — 좌석 한도와 무관하게 항상 유지")
                .isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("개인 구독도 강등 대상이며, 좌석 정지 로직은 적용되지 않는다")
    void 개인구독_만료강등() {
        User person = newUser("만료테스트개인", Role.USER);
        Instant now = Instant.now();
        UserPlan expiring = newPersonalPlan(person.getId(), PlanName.STANDARD,
                now.minusSeconds(31 * 24 * 3600L), now.minusSeconds(60));

        PlanExpiryResult result = planExpiryWriter.expireToFreePlan(expiring.getId(), now);

        assertThat(result.downgraded()).isTrue();
        assertThat(result.companyId()).isNull();
        assertThat(result.userId()).isEqualTo(person.getId());
        assertThat(result.recipientUserId()).isEqualTo(person.getId());
        assertThat(result.suspendedSeatCount())
                .as("개인 구독에는 좌석 개념이 없다 — 좌석 계산·정지 경로를 타면 안 된다")
                .isZero();

        UserPlan renewed = userPlanRepository
                .findFirstByUserIdAndStatusOrderByStartedAtDesc(person.getId(), UserPlanStatus.ACTIVE)
                .orElseThrow();
        createdUserPlanIds.add(renewed.getId());
        assertThat(renewed.getPlanId()).isEqualTo(plan(PlanName.FREE).getId());
        assertThat(renewed.getCurrentPeriodEnd()).isNull();
        assertThat(userRepository.findById(person.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("두 번 실행해도 강등은 1회뿐이고 ACTIVE 구독은 1건으로 유지된다(멱등)")
    void 두번_실행해도_결과가_같다() {
        User owner = newUser("만료테스트멱등", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        Instant now = Instant.now();
        UserPlan expiring = newCompanyPlan(company.getId(), PlanName.STANDARD,
                now.minusSeconds(31 * 24 * 3600L), now.minusSeconds(60));

        PlanExpiryResult first = planExpiryWriter.expireToFreePlan(expiring.getId(), now);
        PlanExpiryResult second = planExpiryWriter.expireToFreePlan(expiring.getId(), now);

        assertThat(first.downgraded()).isTrue();
        assertThat(second.downgraded())
                .as("이미 EXPIRED 로 내려간 구독을 다시 강등하면 이력 행과 알림이 중복 생성된다")
                .isFalse();
        assertThat(activeCompanyPlanCount(company.getId())).isEqualTo(1L);
        assertThat(activeCompanyPlan(company.getId()).getPlanId()).isEqualTo(plan(PlanName.FREE).getId());
        // 새 FREE 구독은 결제주기가 없어 대상 조회에서도 다시 잡히지 않는다.
        assertThat(expiryTargetIds(now)).doesNotContain(activeCompanyPlan(company.getId()).getId());
    }

    @Test
    @DisplayName("조회 이후 결제 주기가 갱신된 구독은 트랜잭션 안 재검증에서 스킵된다(방금 결제한 구독을 내리지 않는다)")
    void 주기가_갱신되면_스킵() {
        User owner = newUser("만료테스트재검증", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        Instant now = Instant.now();
        // 스케줄러가 id 를 읽은 뒤 결제 승인이 주기를 미래로 밀어놓은 상황.
        UserPlan renewedByPayment = newCompanyPlan(company.getId(), PlanName.STANDARD,
                now, now.plusSeconds(30 * 24 * 3600L));

        PlanExpiryResult result = planExpiryWriter.expireToFreePlan(renewedByPayment.getId(), now);

        assertThat(result.downgraded()).isFalse();
        assertThat(userPlanRepository.findById(renewedByPayment.getId()).orElseThrow().getStatus())
                .isEqualTo(UserPlanStatus.ACTIVE);
        assertThat(activeCompanyPlan(company.getId()).getPlanId())
                .isEqualTo(plan(PlanName.STANDARD).getId());
    }

    @Test
    @DisplayName("FREE 구독은 결제 주기가 NULL이라 대상 조회에서 자연 배제된다(plans 가격으로 다시 필터하지 않는다)")
    void FREE구독은_대상이_아니다() {
        User owner = newUser("만료테스트무료", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        Instant now = Instant.now();
        UserPlan freePlan = newCompanyPlan(company.getId(), PlanName.FREE, now.minusSeconds(86_400L), null);

        User person = newUser("만료테스트유료개인", Role.USER);
        UserPlan paidExpired = newPersonalPlan(person.getId(), PlanName.STANDARD,
                now.minusSeconds(31 * 24 * 3600L), now.minusSeconds(60));

        List<Long> targets = expiryTargetIds(now);

        assertThat(targets).contains(paidExpired.getId());
        assertThat(targets)
                .as("FREE 는 current_period_end IS NULL 이라 조회 조건에서 빠진다(#1104 규칙)")
                .doesNotContain(freePlan.getId());
    }

    @Test
    @DisplayName("만료 경계 — 기준시각과 같은 순간은 대상이 아니고, 1밀리초라도 지나면 대상이다(KST 새벽 구간에서도 동일)")
    void 만료_경계_판정() {
        User exact = newUser("만료테스트정각", Role.USER);
        User past = newUser("만료테스트경과", Role.USER);
        User future = newUser("만료테스트미래", Role.USER);
        Instant periodStart = KST_DAWN.minusSeconds(30 * 24 * 3600L);

        UserPlan exactPlan = newPersonalPlan(exact.getId(), PlanName.STANDARD, periodStart, KST_DAWN);
        UserPlan pastPlan = newPersonalPlan(past.getId(), PlanName.STANDARD, periodStart,
                KST_DAWN.minusMillis(1));
        UserPlan futurePlan = newPersonalPlan(future.getId(), PlanName.STANDARD, periodStart,
                KST_DAWN.plusMillis(1));

        List<Long> targets = expiryTargetIds(KST_DAWN);

        // 판정식은 current_period_end < threshold — 정각은 아직 만료가 아니다.
        assertThat(targets).doesNotContain(exactPlan.getId());
        assertThat(targets).contains(pastPlan.getId());
        assertThat(targets).doesNotContain(futurePlan.getId());
        // KST 00:30(UTC 전날 15:30)을 날짜로 절삭했다면 세 건의 판정이 뭉개졌을 자리다.
        assertThat(targets).doesNotContain(futurePlan.getId(), exactPlan.getId());
    }

    @Test
    @DisplayName("스케줄러 실행 시 강등 1건마다 PLAN_EXPIRED 알림이 정확히 1건 발행되고, 재실행해도 중복되지 않는다")
    void 스케줄러_강등알림_1건() {
        User owner = newUser("만료테스트알림", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        Instant now = Instant.now();
        UserPlan expiring = newCompanyPlan(company.getId(), PlanName.STANDARD,
                now.minusSeconds(31 * 24 * 3600L), now.minusSeconds(60));

        planExpiryProperties.setEnabled(true);
        // 공유 컨테이너에 다른 테스트가 남긴 대상 행이 있어도 상한에 걸려 중단되지 않도록 넉넉히 잡는다
        // (상한 자체의 계약은 PlanExpirySchedulerTest 가 목으로 고정한다).
        planExpiryProperties.setMaxPerRun(
                userPlanRepository.findExpiryTargetIds(
                        UserPlanStatus.ACTIVE, Instant.now(), 0L, PageRequest.of(0, 500)).size() + 10);

        planExpiryScheduler.expireOverduePlans();

        assertThat(userPlanRepository.findById(expiring.getId()).orElseThrow().getStatus())
                .isEqualTo(UserPlanStatus.EXPIRED);
        // PG enum 라벨(V28)이 없으면 이 INSERT 자체가 실패한다 — 라벨까지 함께 고정된다.
        assertThat(planExpiredNotifications(owner.getId())).hasSize(1);

        planExpiryScheduler.expireOverduePlans();

        assertThat(planExpiredNotifications(owner.getId()))
                .as("재실행에도 대상이 아니므로 알림이 중복 발행되면 안 된다")
                .hasSize(1);
    }
}
