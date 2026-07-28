package com.hajacheck.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.admin.dto.AdminScheduledPlanChangeResponse;
import com.hajacheck.admin.service.AdminPlanService;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.config.ScheduledPlanChangeProperties;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.ScheduledPlanChange;
import com.hajacheck.membership.entity.ScheduledPlanChangeStatus;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.ScheduledPlanChangeRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.membership.scheduler.ScheduledPlanChangeScheduler;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import com.hajacheck.support.PostgresTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
 * 플랜 하향 예약(#1105 / HAJA-526)의 <b>실제 동작</b>을 실 PostgreSQL(Testcontainers)로 검증한다 —
 * 예약 생성/취소 인가·검증, 예약 시점에 좌석을 건드리지 않음, 적용 시점의 전이·좌석 정지·사용량 이월·
 * 새 결제 주기, 저장된 keep_user_ids 재검증, 멱등성, 무효 예약 취소, PLAN_DOWNGRADED 알림(PG enum
 * 라벨 포함)까지.
 *
 * <p>⚠️ 클래스 레벨 {@code @Transactional} 을 붙이지 않는다 — {@link ScheduledPlanChangeWriter} 가
 * {@code REQUIRES_NEW} 로 독립 트랜잭션을 열기 때문에, 테스트가 트랜잭션을 열고 있으면 픽스처가 아직
 * 커밋되지 않아 writer 쪽에서 보이지 않는다({@code PlanExpiryIntegrationTest} 와 동일한 이유).
 * 커밋된 데이터는 {@link #tearDown()} 에서 직접 정리한다.
 *
 * <p>스케줄러의 통제 로직(비상 스위치·1회 상한·실패 분류·keyset 순회)은 목 기반
 * {@code ScheduledPlanChangeSchedulerTest} 가 담당한다 — 여기서 상한을 다시 검증하지 않는 이유는, 이
 * 테스트가 공유 컨테이너의 <b>전역 대상 건수</b>에 의존하게 되어 다른 테스트가 남긴 행에 흔들리기 때문이다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ScheduledPlanChangeIntegrationTest extends PostgresTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private AdminPlanService adminPlanService;
    @Autowired
    private ScheduledPlanChangeWriter scheduledPlanChangeWriter;
    @Autowired
    private PlanExpiryWriter planExpiryWriter;
    @Autowired
    private ScheduledPlanChangeScheduler scheduledPlanChangeScheduler;
    @Autowired
    private ScheduledPlanChangeProperties scheduledPlanChangeProperties;
    @Autowired
    private ScheduledPlanChangeRepository scheduledPlanChangeRepository;
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
    @PersistenceContext
    private EntityManager entityManager;

    private final List<Long> createdUserPlanIds = new ArrayList<>();
    private final List<Long> createdMembershipIds = new ArrayList<>();
    private final List<Long> createdCompanyIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private int originalMaxPerRun;
    private boolean originalEnabled;

    @BeforeEach
    void captureProperties() {
        // 싱글턴 빈이라 테스트가 바꾼 값이 다음 테스트로 새지 않게 원복한다.
        originalMaxPerRun = scheduledPlanChangeProperties.getMaxPerRun();
        originalEnabled = scheduledPlanChangeProperties.isEnabled();
    }

    @AfterEach
    void tearDown() {
        scheduledPlanChangeProperties.setMaxPerRun(originalMaxPerRun);
        scheduledPlanChangeProperties.setEnabled(originalEnabled);

        createdUserIds.forEach(userId -> notificationRepository
                .findAllByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 100))
                .forEach(notificationRepository::delete));

        // 예약 하향으로 새로 발급된 구독까지 포함해 이 테스트가 만든 회사의 구독을 전부 지운다.
        List<Long> planIdsToDelete = new ArrayList<>(createdUserPlanIds);
        for (Long companyId : createdCompanyIds) {
            userPlanRepository.findByCompanyIdIsNotNull().stream()
                    .filter(up -> companyId.equals(up.getCompanyId()))
                    .map(UserPlan::getId)
                    .forEach(planIdsToDelete::add);
        }
        planIdsToDelete.stream().distinct().forEach(userPlanId -> {
            // scheduled_plan_changes 가 user_plans 를 FK 로 참조하므로 구독보다 먼저 지운다.
            jdbcTemplate.update("delete from scheduled_plan_changes where user_plan_id = ?", userPlanId);
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
        String brn = "sbrn-" + (System.nanoTime() % 10_000_000_000L);
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)예약하향테스트", brn,
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
        User member = newUser("예약하향구성원", Role.USER);
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

    /**
     * 결제 주기를 원하는 값으로 직접 박는다(엔티티에는 임의 시각 지정 수단이 없다).
     *
     * <p>⚠️ 마이크로초로 절삭해 저장한다. PostgreSQL {@code timestamptz} 는 마이크로초(6자리)까지만
     * 보관하고 그 아래 자리를 <b>반올림</b>한다(절삭이 아니다). {@code Instant.now()} 가 나노초를 주는
     * 플랫폼(리눅스 CI)에서 원본을 그대로 넣으면 저장값이 1μs 밀려, 적용 시각·주기 승계를 비교하는 단정이
     * CI 에서만 깨진다(macOS 는 {@code Instant.now()} 자체가 마이크로초라 로컬에서 재현되지 않는다).
     */
    private void setBillingPeriod(Long userPlanId, Instant periodStart, Instant periodEnd) {
        jdbcTemplate.update(
                "update user_plans set current_period_start = ?, current_period_end = ? where id = ?",
                periodStart == null ? null : java.sql.Timestamp.from(periodStart.truncatedTo(ChronoUnit.MICROS)),
                periodEnd == null ? null : java.sql.Timestamp.from(periodEnd.truncatedTo(ChronoUnit.MICROS)),
                userPlanId);
        userPlanRepository.flush();
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

    private ScheduledPlanChange reload(Long scheduledChangeId) {
        return scheduledPlanChangeRepository.findById(scheduledChangeId).orElseThrow();
    }

    private List<Notification> downgradedNotifications(Long userId) {
        return notificationsOf(userId, NotificationType.PLAN_DOWNGRADED);
    }

    private List<Notification> notificationsOf(Long userId, NotificationType type) {
        return notificationRepository
                .findAllByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 100))
                .stream()
                .filter(n -> n.getType() == type)
                .toList();
    }

    /**
     * 요금제 좌석 한도를 직접 바꾼다(플랫폼 관리자 "플랜 정책 설정"과 같은 결과, #624). 시드 행을
     * 건드리므로 호출한 테스트가 <b>반드시 원복</b>해야 한다 — FREE=1석은 티어 설계 의도이지 결함이 아니다.
     */
    private void setPlanMaxSeats(PlanName planName, Integer maxSeats) {
        jdbcTemplate.update("update plans set max_seats = ? where name = ?::plan_name_type",
                maxSeats, planName.name());
        planRepository.flush();
        // 1차 캐시에 남은 옛 Plan 스냅샷이 이후 조회에 섞이지 않게 비운다.
        entityManager.clear();
    }

    private UserStatus statusOf(User user) {
        return userRepository.findById(user.getId()).orElseThrow().getStatus();
    }

    /**
     * 공유 컨테이너에 다른 테스트가 남긴 대상 행이 있어도 1회 상한에 걸려 중단되지 않도록 여유를 둔다
     * (상한 자체의 계약은 {@code ScheduledPlanChangeSchedulerTest} 가 목으로 고정한다).
     */
    private void giveSchedulerHeadroom() {
        scheduledPlanChangeProperties.setEnabled(true);
        scheduledPlanChangeProperties.setMaxPerRun(
                (int) scheduledPlanChangeRepository.countDue(Instant.now()) + 10);
    }

    // ── 예약 생성 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("하향 예약은 예약 행만 만들고 구독·좌석을 전혀 건드리지 않는다(잔여 기간은 상위 요금제 유지)")
    void 예약은_좌석을_건드리지_않는다() {
        User owner = newUser("예약하향대표", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        User member1 = newCompanyMember(company.getId());
        User member2 = newCompanyMember(company.getId());
        User member3 = newCompanyMember(company.getId());
        Instant now = Instant.now();
        Instant periodEnd = now.plusSeconds(10 * 24 * 3600L);
        // ENTERPRISE(좌석 무제한) → FREE(1석). 활성 4명이라 적용 시점에 owner 를 뺀 3명이 정지된다.
        UserPlan current = newCompanyPlan(company.getId(), PlanName.ENTERPRISE,
                now.minusSeconds(20 * 24 * 3600L), periodEnd);

        AdminScheduledPlanChangeResponse response = adminPlanService.scheduleChange(
                owner.getId(), PlanName.FREE, true, List.of());

        assertThat(response.targetPlanName()).isEqualTo("FREE");
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.effectiveAt())
                .as("적용 시각은 신청 시점의 결제 주기 종료 시각(#1104)이다 — 그 시점이 잔여 기간의 끝이다")
                .isEqualTo(periodEnd.truncatedTo(ChronoUnit.MICROS));

        // 예약은 신청만 기록한다 — 여기서 좌석이 정지되면 "다음 결제 주기 적용"이라는 기능의 존재 이유가 사라진다.
        assertThat(activeCompanyPlan(company.getId()).getPlanId())
                .isEqualTo(plan(PlanName.ENTERPRISE).getId());
        assertThat(activeCompanyPlan(company.getId()).getId()).isEqualTo(current.getId());
        assertThat(statusOf(member1)).isEqualTo(UserStatus.ACTIVE);
        assertThat(statusOf(member2)).isEqualTo(UserStatus.ACTIVE);
        assertThat(statusOf(member3)).isEqualTo(UserStatus.ACTIVE);

        // 현재 플랜 조회에 대기 예약이 노출된다(프론트가 "예약 있음"을 이 필드로 판정한다).
        assertThat(adminPlanService.getCurrentPlan(owner.getId()).scheduledChange())
                .isNotNull()
                .satisfies(scheduled -> assertThat(scheduled.targetPlanName()).isEqualTo("FREE"));
    }

    @Test
    @DisplayName("이미 대기 중인 예약이 있으면 중복 예약을 거절한다(부분 UQ 와 같은 계약)")
    void 중복_예약_거절() {
        User owner = newUser("예약하향중복", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        Instant now = Instant.now();
        newCompanyPlan(company.getId(), PlanName.ENTERPRISE,
                now.minusSeconds(86_400L), now.plusSeconds(10 * 24 * 3600L));

        adminPlanService.scheduleChange(owner.getId(), PlanName.FREE, true, List.of());

        assertThatThrownBy(() -> adminPlanService.scheduleChange(
                owner.getId(), PlanName.FREE, true, List.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_SCHEDULED_CHANGE_EXISTS));
    }

    @Test
    @DisplayName("상향은 예약할 수 없다 — 상향은 결제 경로 전용이고 즉시 적용이라 예약 개념이 없다")
    void 상향은_예약할_수_없다() {
        User owner = newUser("예약하향상향", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        Instant now = Instant.now();
        newCompanyPlan(company.getId(), PlanName.STANDARD,
                now.minusSeconds(86_400L), now.plusSeconds(10 * 24 * 3600L));

        assertThatThrownBy(() -> adminPlanService.scheduleChange(
                owner.getId(), PlanName.ENTERPRISE, true, List.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_SCHEDULE_NOT_DOWNGRADE));

        // 같은 요금제로의 "변경"도 기다릴 이유가 없어 같은 코드로 거절한다.
        assertThatThrownBy(() -> adminPlanService.scheduleChange(
                owner.getId(), PlanName.STANDARD, true, List.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_SCHEDULE_NOT_DOWNGRADE));
    }

    @Test
    @DisplayName("다음 결제일이 없는 구독은 예약할 수 없다 — 실행 기준 시각을 정할 수 없다")
    void 결제주기가_없으면_예약할_수_없다() {
        User owner = newUser("예약하향무기한", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        Instant now = Instant.now();
        // 유료 플랜인데 current_period_end 가 비어 있는 상태(데이터 이상)를 재현한다.
        newCompanyPlan(company.getId(), PlanName.STANDARD, now.minusSeconds(86_400L), null);

        assertThatThrownBy(() -> adminPlanService.scheduleChange(
                owner.getId(), PlanName.FREE, true, List.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_SCHEDULE_PERIOD_END_MISSING));
    }

    @Test
    @DisplayName("초과 좌석이 생기는데 확인 플래그가 없으면 예약을 만들지 않는다 — 실제 정지는 한 달 뒤 사람 없이 일어난다")
    void 확인없는_초과예약은_거절된다() {
        User owner = newUser("예약하향미확인", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        newCompanyMember(company.getId());
        newCompanyMember(company.getId());
        newCompanyMember(company.getId());
        Instant now = Instant.now();
        UserPlan current = newCompanyPlan(company.getId(), PlanName.ENTERPRISE,
                now.minusSeconds(86_400L), now.plusSeconds(10 * 24 * 3600L));

        assertThatThrownBy(() -> adminPlanService.scheduleChange(
                owner.getId(), PlanName.FREE, false, List.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_DOWNGRADE_CONFIRMATION_REQUIRED));

        assertThat(scheduledPlanChangeRepository.findFirstByUserPlanIdAndStatus(
                current.getId(), ScheduledPlanChangeStatus.PENDING))
                .as("거절된 요청이 예약 행을 남기면 한 달 뒤 아무도 모르는 사이 계정이 정지된다")
                .isEmpty();
    }

    @Test
    @DisplayName("회사 owner 가 아닌 관리자는 예약·취소를 할 수 없다(인가는 즉시 변경과 동일)")
    void owner가_아니면_예약할_수_없다() {
        User owner = newUser("예약하향소유자", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        User otherAdmin = newUser("예약하향타관리자", Role.ADMIN);
        otherAdmin.assignToCompany(company.getId());
        userRepository.save(otherAdmin);
        Instant now = Instant.now();
        newCompanyPlan(company.getId(), PlanName.ENTERPRISE,
                now.minusSeconds(86_400L), now.plusSeconds(10 * 24 * 3600L));

        assertThatThrownBy(() -> adminPlanService.scheduleChange(
                otherAdmin.getId(), PlanName.FREE, true, List.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_FORBIDDEN));
        assertThatThrownBy(() -> adminPlanService.cancelScheduledChange(otherAdmin.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_FORBIDDEN));
    }

    // ── 예약 취소 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("예약을 취소하면 CANCELED 로 종료되고, 다시 취소하면 404 다(조회가 아니라 갱신 행 수로 판정)")
    void 예약_취소() {
        User owner = newUser("예약하향취소", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        Instant now = Instant.now();
        newCompanyPlan(company.getId(), PlanName.ENTERPRISE,
                now.minusSeconds(86_400L), now.plusSeconds(10 * 24 * 3600L));
        AdminScheduledPlanChangeResponse scheduled = adminPlanService.scheduleChange(
                owner.getId(), PlanName.FREE, true, List.of());

        AdminScheduledPlanChangeResponse canceledResponse =
                adminPlanService.cancelScheduledChange(owner.getId());

        assertThat(canceledResponse.id()).isEqualTo(scheduled.id());
        assertThat(canceledResponse.status())
                .as("취소 응답이 PENDING 으로 나가면 프론트가 '취소했는데 대기 중'으로 표시한다(1차 캐시 스냅샷 함정)")
                .isEqualTo("CANCELED");
        assertThat(reload(scheduled.id()).getStatus()).isEqualTo(ScheduledPlanChangeStatus.CANCELED);
        assertThat(adminPlanService.getCurrentPlan(owner.getId()).scheduledChange())
                .as("취소된 예약이 화면에 남으면 '예약 있음'으로 오인한다")
                .isNull();
        assertThatThrownBy(() -> adminPlanService.cancelScheduledChange(owner.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_SCHEDULED_CHANGE_NOT_FOUND));

        // 취소했으면 같은 조건으로 다시 예약할 수 있어야 한다(부분 UQ 는 PENDING 만 대상이다).
        assertThat(adminPlanService.scheduleChange(owner.getId(), PlanName.FREE, true, List.of()))
                .isNotNull();
    }

    @Test
    @DisplayName("즉시 변경(PATCH /api/admin/plan)이 일어나면 대기 예약은 자동으로 무효화된다")
    void 즉시변경시_예약이_자동_취소된다() {
        User owner = newUser("예약하향즉시변경", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        Instant now = Instant.now();
        newCompanyPlan(company.getId(), PlanName.ENTERPRISE,
                now.minusSeconds(86_400L), now.plusSeconds(10 * 24 * 3600L));
        AdminScheduledPlanChangeResponse scheduled = adminPlanService.scheduleChange(
                owner.getId(), PlanName.FREE, true, List.of());

        adminPlanService.changePlan(owner.getId(), PlanName.STANDARD, true, List.of());

        assertThat(reload(scheduled.id()).getStatus())
                .as("남겨 두면 한 달 뒤 스케줄러가 '이미 반영된 하향'을 한 번 더 실행하려 든다")
                .isEqualTo(ScheduledPlanChangeStatus.CANCELED);
    }

    // ── 예약 적용(스케줄러) ─────────────────────────────────────────────

    @Test
    @DisplayName("적용 시각이 지나면 하향이 반영되고 초과 좌석 정지·사용량 이월·새 결제 주기 개시가 함께 일어난다")
    void 예약_적용() {
        User owner = newUser("예약하향적용", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        User member1 = newCompanyMember(company.getId());
        User member2 = newCompanyMember(company.getId());
        User member3 = newCompanyMember(company.getId());
        User member4 = newCompanyMember(company.getId());
        Instant now = Instant.now();
        Instant effectiveAt = now.minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        // ENTERPRISE(무제한) → FREE(1석). 활성 5명이라 owner 만 남고 나머지 4명이 정지된다.
        UserPlan current = newCompanyPlan(company.getId(), PlanName.ENTERPRISE,
                now.minusSeconds(31 * 24 * 3600L), effectiveAt);
        usageCounterRepository.saveAndFlush(UsageCounter.create(
                current.getId(), currentPeriod(), 7, 2, 3, 3, 1, 0));
        AdminScheduledPlanChangeResponse scheduled = adminPlanService.scheduleChange(
                owner.getId(), PlanName.FREE, true, List.of());

        ScheduledPlanChangeResult result = scheduledPlanChangeWriter.applyDueChange(scheduled.id(), now);

        assertThat(result.applied()).isTrue();
        assertThat(result.previousPlanName()).isEqualTo(PlanName.ENTERPRISE);
        assertThat(result.targetPlanName()).isEqualTo(PlanName.FREE);
        assertThat(result.recipientUserId())
                .as("회사 구독의 알림 수신자는 회사 owner 다")
                .isEqualTo(owner.getId());
        assertThat(result.suspendedUserIds())
                .as("오적용 시 되돌릴 대상 목록을 복원할 유일한 근거다 — 건수만으로는 부족하다")
                .containsExactlyInAnyOrder(member1.getId(), member2.getId(), member3.getId(), member4.getId());

        assertThat(reload(scheduled.id()).getStatus()).isEqualTo(ScheduledPlanChangeStatus.APPLIED);
        assertThat(reload(scheduled.id()).getAppliedAt()).isNotNull();

        assertThat(userPlanRepository.findById(current.getId()).orElseThrow().getStatus())
                .isEqualTo(UserPlanStatus.EXPIRED);
        UserPlan renewed = activeCompanyPlan(company.getId());
        assertThat(renewed.getPlanId()).isEqualTo(plan(PlanName.FREE).getId());
        assertThat(activeCompanyPlanCount(company.getId())).isEqualTo(1L);

        // ⚠️ 대상이 무료라 결제 주기 종료는 NULL(무기한)이다(#1104 규칙). 여기에 값이 남으면 마이페이지가
        // "무료인데 다음 결제일"을 표시하고 만료 강등 배치가 이 행을 다시 대상으로 잡는다. 유료 대상을
        // 허용하지 않는 이유(무결제 유료 1개월 발급)는 ScheduledPlanChangeWriter javadoc 참고.
        assertThat(renewed.getCurrentPeriodEnd()).isNull();
        assertThat(renewed.getCurrentPeriodStart().truncatedTo(ChronoUnit.MICROS))
                .as("주기 시작은 승계한다 — 구독 개시 시점 자체는 여전히 유효한 정보다")
                .isEqualTo(now.minusSeconds(31 * 24 * 3600L).truncatedTo(ChronoUnit.MICROS));

        // 사용량 이월(#851) — 이월하지 않으면 예약 하향이 곧 월 분석 한도 리셋이 된다.
        UsageCounter carried = usageCounterRepository
                .findByUserPlanIdAndPeriod(renewed.getId(), currentPeriod()).orElseThrow();
        assertThat(carried.getAnalyzedImageCount()).isEqualTo(7);

        assertThat(statusOf(owner))
                .as("owner 가 정지되면 회사가 관리 불능이 된다 — 좌석 한도와 무관하게 항상 유지")
                .isEqualTo(UserStatus.ACTIVE);
        assertThat(statusOf(member1)).isEqualTo(UserStatus.SUSPENDED);
        assertThat(statusOf(member2)).isEqualTo(UserStatus.SUSPENDED);
        assertThat(statusOf(member3)).isEqualTo(UserStatus.SUSPENDED);
        assertThat(statusOf(member4)).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    @DisplayName("두 번 실행해도 적용은 1회뿐이다(UPDATE ... WHERE status=PENDING 조건부 전이)")
    void 두번_실행해도_결과가_같다() {
        User owner = newUser("예약하향멱등", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        Instant now = Instant.now();
        newCompanyPlan(company.getId(), PlanName.ENTERPRISE,
                now.minusSeconds(31 * 24 * 3600L), now.minusSeconds(60));
        AdminScheduledPlanChangeResponse scheduled = adminPlanService.scheduleChange(
                owner.getId(), PlanName.FREE, true, List.of());

        ScheduledPlanChangeResult first = scheduledPlanChangeWriter.applyDueChange(scheduled.id(), now);
        ScheduledPlanChangeResult second = scheduledPlanChangeWriter.applyDueChange(scheduled.id(), now);

        assertThat(first.applied()).isTrue();
        assertThat(second.applied())
                .as("두 번 적용되면 이력 행과 알림이 중복 생성되고 좌석 정지가 두 번 계산된다")
                .isFalse();
        assertThat(activeCompanyPlanCount(company.getId())).isEqualTo(1L);
        assertThat(activeCompanyPlan(company.getId()).getPlanId())
                .isEqualTo(plan(PlanName.FREE).getId());
    }

    @Test
    @DisplayName("적용 시각 전에는 대상 조회에 잡히지 않고, 강제로 호출해도 적용되지 않는다")
    void 적용시각_전에는_실행되지_않는다() {
        User owner = newUser("예약하향미래", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        Instant now = Instant.now();
        Instant future = now.plusSeconds(10 * 24 * 3600L);
        newCompanyPlan(company.getId(), PlanName.ENTERPRISE, now.minusSeconds(86_400L), future);
        AdminScheduledPlanChangeResponse scheduled = adminPlanService.scheduleChange(
                owner.getId(), PlanName.FREE, true, List.of());

        assertThat(scheduledPlanChangeRepository.findDueIds(now, 0L, PageRequest.of(0, 500)))
                .doesNotContain(scheduled.id());

        ScheduledPlanChangeResult result = scheduledPlanChangeWriter.applyDueChange(scheduled.id(), now);

        assertThat(result.applied())
                .as("조기 적용은 절대 금지 — 이미 낸 요금 기간을 빼앗는 것이다")
                .isFalse();
        assertThat(reload(scheduled.id()).getStatus()).isEqualTo(ScheduledPlanChangeStatus.PENDING);
        assertThat(activeCompanyPlan(company.getId()).getPlanId())
                .isEqualTo(plan(PlanName.ENTERPRISE).getId());
    }

    @Test
    @DisplayName("예약 이후 구독이 다른 경로로 전이됐으면 적용하지 않고 예약을 무효(CANCELED)로 종료한다")
    void 구독이_이미_전이됐으면_무효처리한다() {
        User owner = newUser("예약하향전이", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        Instant now = Instant.now();
        UserPlan current = newCompanyPlan(company.getId(), PlanName.ENTERPRISE,
                now.minusSeconds(31 * 24 * 3600L), now.minusSeconds(60));
        AdminScheduledPlanChangeResponse scheduled = adminPlanService.scheduleChange(
                owner.getId(), PlanName.FREE, true, List.of());
        // 예약이 걸린 구독을 SQL 로 직접 만료시킨다(다른 경로가 먼저 전이시킨 상황 — 자동 취소 경로를
        // 타지 않고도 무효 판정이 동작하는지 봐야 하므로 서비스가 아니라 SQL 로 만든다).
        jdbcTemplate.update("update user_plans set status = 'EXPIRED' where id = ?", current.getId());
        userPlanRepository.flush();

        ScheduledPlanChangeResult result = scheduledPlanChangeWriter.applyDueChange(scheduled.id(), now);

        assertThat(result.applied()).isFalse();
        assertThat(result.canceled()).isTrue();
        assertThat(reload(scheduled.id()).getStatus()).isEqualTo(ScheduledPlanChangeStatus.CANCELED);
        assertThat(reload(scheduled.id()).getFailureReason()).contains("EXPIRED");
    }

    @Test
    @DisplayName("저장된 유지 대상 중 퇴사·정지된 id 는 드롭되고, 되채움은 드롭된 수만큼만 한다(확인한 정지 규모 유지)")
    void 유지대상_재검증() {
        User owner = newUser("예약하향유지검증", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        User member1 = newCompanyMember(company.getId());
        User member2 = newCompanyMember(company.getId());
        User member3 = newCompanyMember(company.getId());
        User member4 = newCompanyMember(company.getId());
        Instant now = Instant.now();
        UserPlan current = newCompanyPlan(company.getId(), PlanName.ENTERPRISE,
                now.minusSeconds(31 * 24 * 3600L), now.minusSeconds(60));

        // 예약 대상은 무료 요금제뿐인데(보안 리뷰 P1) FREE 는 1석이라 유지 대상 선택 자체가 불가능하다.
        // 유지 목록 재검증 로직은 좌석이 2 이상인 무료 티어에서만 의미가 있으므로, 플랫폼 관리자가 FREE
        // 좌석 정책을 올린 상황(#624 Plan#updatePolicy)을 재현해 그 경로를 검증한다. 시드 값을 건드리므로
        // 반드시 원복한다(FREE=1석은 티어 설계 의도이며 여기서 바꾸려는 게 아니다).
        Integer originalFreeSeats = plan(PlanName.FREE).getMaxSeats();
        setPlanMaxSeats(PlanName.FREE, 3);
        try {
            // owner + member1 + member2 를 유지하도록 예약한다(3석) → 확인 시점 정지 대상은 2명.
            AdminScheduledPlanChangeResponse scheduled = adminPlanService.scheduleChange(
                    owner.getId(), PlanName.FREE, true, List.of(member1.getId(), member2.getId()));
            assertThat(reload(scheduled.id()).keepUserIdList())
                    .containsExactly(member1.getId(), member2.getId());
            assertThat(reload(scheduled.id()).getConfirmedSeatOverflow()).isEqualTo(2);

            // 예약 이후 member1 이 정지됐다(퇴사·정지). 저장된 목록을 그대로 넘기면
            // PlanDowngradeService 의 스코프 검증(PLAN_KEEP_USER_INVALID)에 걸려 예약이 통째로 죽는다.
            User staleMember = userRepository.findById(member1.getId()).orElseThrow();
            staleMember.changeStatus(UserStatus.SUSPENDED);
            userRepository.saveAndFlush(staleMember);

            ScheduledPlanChangeResult result = scheduledPlanChangeWriter.applyDueChange(scheduled.id(), now);

            assertThat(result.applied())
                    .as("무효 id 하나 때문에 예약이 실패하면 안 된다 — 드롭 후 그만큼만 되채운다")
                    .isTrue();
            assertThat(statusOf(member2))
                    .as("여전히 유효한 선택은 그대로 존중한다")
                    .isEqualTo(UserStatus.ACTIVE);
            assertThat(statusOf(owner)).isEqualTo(UserStatus.ACTIVE);
            // 드롭 1건 → 되채움 예산도 1건. owner 는 어차피 항상 유지되므로 예산을 쓰지 않고 건너뛰고
            // (그러지 않으면 생존자는 그대로인데 예산만 소모된다) id 오름차순으로 member3 가 들어온다.
            assertThat(statusOf(member3))
                    .as("드롭된 자리는 자동 규칙(id 오름차순)으로 정확히 그만큼만 채운다")
                    .isEqualTo(UserStatus.ACTIVE);
            assertThat(statusOf(member4)).isEqualTo(UserStatus.SUSPENDED);
            assertThat(result.suspendedUserIds())
                    .as("확인받은 정지 규모(2명)를 넘지 않아야 한다")
                    .containsExactly(member4.getId());
            assertThat(userPlanRepository.findById(current.getId()).orElseThrow().getStatus())
                    .isEqualTo(UserPlanStatus.EXPIRED);
        } finally {
            setPlanMaxSeats(PlanName.FREE, originalFreeSeats);
        }
    }

    @Test
    @DisplayName("드롭이 없으면 좌석이 남아도 임의로 채우지 않는다 — 관리자가 확인한 정지 대상과 결과가 어긋나면 안 된다")
    void 드롭이_없으면_보충하지_않는다() {
        User owner = newUser("예약하향무보충", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        User member1 = newCompanyMember(company.getId());
        User member2 = newCompanyMember(company.getId());
        User member3 = newCompanyMember(company.getId());
        Instant now = Instant.now();
        newCompanyPlan(company.getId(), PlanName.ENTERPRISE,
                now.minusSeconds(31 * 24 * 3600L), now.minusSeconds(60));

        Integer originalFreeSeats = plan(PlanName.FREE).getMaxSeats();
        setPlanMaxSeats(PlanName.FREE, 3);
        try {
            // owner + member1 만 유지 선택(3석 중 2석만 사용) → 확인 시점 정지 대상은 member2·member3 2명.
            AdminScheduledPlanChangeResponse scheduled = adminPlanService.scheduleChange(
                    owner.getId(), PlanName.FREE, true, List.of(member1.getId()));
            assertThat(reload(scheduled.id()).getConfirmedSeatOverflow()).isEqualTo(2);

            ScheduledPlanChangeResult result = scheduledPlanChangeWriter.applyDueChange(scheduled.id(), now);

            assertThat(result.applied()).isTrue();
            assertThat(result.suspendedUserIds())
                    .as("무효화된 id 가 하나도 없는데 좌석이 남는다고 채우면, 확인한 정지 대상(2명)과 "
                            + "실제 결과(1명)가 달라진다 — 즉시 변경 경로는 '선택을 있는 그대로 존중'한다")
                    .containsExactlyInAnyOrder(member2.getId(), member3.getId());
            assertThat(statusOf(member1)).isEqualTo(UserStatus.ACTIVE);
            assertThat(statusOf(owner)).isEqualTo(UserStatus.ACTIVE);
        } finally {
            setPlanMaxSeats(PlanName.FREE, originalFreeSeats);
        }
    }

    // ── 보안 리뷰 P1 / P2 회귀선 ────────────────────────────────────────

    @Test
    @DisplayName("유료 요금제는 예약 대상이 될 수 없다 — 청구되지 않는 유료 1개월이 발급되는 것을 막는다")
    void 유료대상은_예약할_수_없다() {
        User owner = newUser("예약하향유료대상", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        Instant now = Instant.now();
        UserPlan current = newCompanyPlan(company.getId(), PlanName.ENTERPRISE,
                now.minusSeconds(86_400L), now.plusSeconds(10 * 24 * 3600L));

        // ENTERPRISE → STANDARD 는 정상적인 "하향"이지만, 실행 시 결제 없이 새 유료 주기가 열린다.
        // 빌링키가 없어 그 주기는 어떤 경로로도 청구되지 않으므로 티어를 한 단계씩 내리는 것만으로
        // 무상 1개월을 반복 취득할 수 있다(#988이 막은 무결제 승격과 같은 성격의 우회로).
        assertThatThrownBy(() -> adminPlanService.scheduleChange(
                owner.getId(), PlanName.STANDARD, true, List.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_SCHEDULE_PAID_TARGET_UNSUPPORTED));

        assertThat(scheduledPlanChangeRepository.findFirstByUserPlanIdAndStatus(
                current.getId(), ScheduledPlanChangeStatus.PENDING)).isEmpty();
    }

    @Test
    @DisplayName("실행 시점 정지 인원이 신청 시 확인한 수를 넘으면 적용하지 않고 FAILED 로 종료한다(+실패 알림)")
    void 확인범위를_넘으면_적용하지_않는다() {
        User owner = newUser("예약하향확인범위", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        User member1 = newCompanyMember(company.getId());
        Instant now = Instant.now();
        UserPlan current = newCompanyPlan(company.getId(), PlanName.ENTERPRISE,
                now.minusSeconds(31 * 24 * 3600L), now.minusSeconds(60));
        // 신청 시점 정지 대상은 member1 하나뿐 — 관리자는 "1명 정지"에 동의했다.
        AdminScheduledPlanChangeResponse scheduled = adminPlanService.scheduleChange(
                owner.getId(), PlanName.FREE, true, List.of());
        assertThat(reload(scheduled.id()).getConfirmedSeatOverflow()).isEqualTo(1);

        // 예약 이후 구성원이 늘었다 — 이제 실행하면 3명이 정지된다(동의 범위를 크게 벗어난다).
        User member2 = newCompanyMember(company.getId());
        User member3 = newCompanyMember(company.getId());

        giveSchedulerHeadroom();
        scheduledPlanChangeScheduler.applyDueScheduledChanges();

        assertThat(reload(scheduled.id()).getStatus())
                .as("동의 범위를 넘는 대량 정지는 적용하지 않고 종료시킨다(fail-safe)")
                .isEqualTo(ScheduledPlanChangeStatus.FAILED);
        assertThat(reload(scheduled.id()).getFailureReason())
                .contains("PLAN_SCHEDULE_CONFIRMED_OVERFLOW_EXCEEDED");
        // 상위 요금제가 그대로 유지되므로 아무도 잘못 정지되지 않는다.
        assertThat(userPlanRepository.findById(current.getId()).orElseThrow().getStatus())
                .isEqualTo(UserPlanStatus.ACTIVE);
        assertThat(activeCompanyPlan(company.getId()).getPlanId())
                .isEqualTo(plan(PlanName.ENTERPRISE).getId());
        assertThat(statusOf(member1)).isEqualTo(UserStatus.ACTIVE);
        assertThat(statusOf(member2)).isEqualTo(UserStatus.ACTIVE);
        assertThat(statusOf(member3)).isEqualTo(UserStatus.ACTIVE);

        // FAILED 는 종료 상태라 재시도가 없고 조회는 PENDING 만 노출한다 — 알림이 없으면 신청자는
        // 예약이 사라진 사실을 영원히 모른다(리뷰 P2-5).
        assertThat(notificationsOf(owner.getId(), NotificationType.PLAN_DOWNGRADE_FAILED))
                .hasSize(1);
        assertThat(downgradedNotifications(owner.getId()))
                .as("적용되지 않았으므로 적용 알림은 나가면 안 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("만료 강등이 구독을 내리면 그 구독의 예약도 함께 무효화된다 — owner 가 취소할 수 없는 유령 예약 방지")
    void 만료강등시_예약이_무효화된다() {
        User owner = newUser("예약하향만료강등", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        Instant now = Instant.now();
        UserPlan current = newCompanyPlan(company.getId(), PlanName.ENTERPRISE,
                now.minusSeconds(31 * 24 * 3600L), now.minusSeconds(60));
        AdminScheduledPlanChangeResponse scheduled = adminPlanService.scheduleChange(
                owner.getId(), PlanName.FREE, true, List.of());

        // 결제 주기 만료 강등(#1145)이 먼저 이 구독을 EXPIRED 로 내린다.
        planExpiryWriter.expireToFreePlan(current.getId(), now);

        assertThat(reload(scheduled.id()).getStatus())
                .as("남겨 두면 예약이 옛 user_plan_id 에 매달린 채 PENDING 으로 남아, 조회·취소가 모두 "
                        + "현재 구독 id 기준이라 owner 가 취소할 수 없는 유령 예약이 된다")
                .isEqualTo(ScheduledPlanChangeStatus.CANCELED);
        assertThat(adminPlanService.getCurrentPlan(owner.getId()).scheduledChange()).isNull();
    }

    @Test
    @DisplayName("스케줄러 실행 시 적용 1건마다 PLAN_DOWNGRADED 알림이 정확히 1건 발행되고, 재실행해도 중복되지 않는다")
    void 스케줄러_적용알림_1건() {
        User owner = newUser("예약하향알림", Role.ADMIN);
        Company company = newApprovedCompany(owner);
        Instant now = Instant.now();
        UserPlan current = newCompanyPlan(company.getId(), PlanName.ENTERPRISE,
                now.minusSeconds(31 * 24 * 3600L), now.minusSeconds(60));
        AdminScheduledPlanChangeResponse scheduled = adminPlanService.scheduleChange(
                owner.getId(), PlanName.FREE, true, List.of());

        giveSchedulerHeadroom();
        scheduledPlanChangeScheduler.applyDueScheduledChanges();

        assertThat(userPlanRepository.findById(current.getId()).orElseThrow().getStatus())
                .isEqualTo(UserPlanStatus.EXPIRED);
        assertThat(reload(scheduled.id()).getStatus()).isEqualTo(ScheduledPlanChangeStatus.APPLIED);
        // PG enum 라벨(V31)이 없으면 이 INSERT 자체가 실패한다 — 라벨까지 함께 고정된다.
        assertThat(downgradedNotifications(owner.getId())).hasSize(1);

        scheduledPlanChangeScheduler.applyDueScheduledChanges();

        assertThat(downgradedNotifications(owner.getId()))
                .as("재실행에도 대상이 아니므로 알림이 중복 발행되면 안 된다")
                .hasSize(1);
    }
}
