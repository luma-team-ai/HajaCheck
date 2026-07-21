package com.hajacheck.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.admin.dto.AdminUserProjection;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

/**
 * 관리자 사용자 관리(#405) — 검색/필터/페이징 JPQL 쿼리 + 통계 집계 쿼리 검증.
 * MembershipRepositoryTest 와 동일 패턴(PostgresTestSupport, ddl-auto=validate).
 *
 * <p>기업 관리자 전용 화면이라 모든 조회는 companyId로 스코핑된다 — 기본 companyId(companyA)로
 * 사용자를 저장하고, 크로스 테넌트 격리를 확인하는 테스트만 별도 companyId(companyB)를 쓴다.
 * users.company_id / companies.business_registration_number 는 DDL 상 FK/unique 이므로 리터럴
 * id 대신 매 테스트마다 실제 Company 행을 저장하고 그 id 를 쓴다(FK 위반 방지).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class AdminUserRepositoryTest extends PostgresTestSupport {

    private static final AtomicLong BRN_SEQ = new AtomicLong(9_100_000_000L);

    @Autowired
    private AdminUserRepository adminUserRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private UserPlanRepository userPlanRepository;

    private Long companyA;
    private Long companyB;

    @BeforeEach
    void setUpCompanies() {
        companyA = saveCompany().getId();
        companyB = saveCompany().getId();
    }

    private Company saveCompany() {
        long brn = BRN_SEQ.getAndIncrement();
        User owner = userRepository.save(User.builder()
                .email("owner" + brn + "@haja.com")
                .name("대표")
                .role(Role.ADMIN)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
        return companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)테스트", String.valueOf(brn), "김대표",
                "서울시 강남구", null, "http://files/brn.png", "{}"));
    }

    private User saveUser(String name, String email, Role role, UserStatus status) {
        return saveUser(name, email, role, status, companyA);
    }

    private User saveUser(String name, String email, Role role, UserStatus status, Long companyId) {
        return userRepository.save(User.builder()
                .email(email)
                .name(name)
                .role(role)
                .companyId(companyId)
                .passwordHash("$2a$10$hashed")
                .status(status)
                .build());
    }

    private Plan savePlan(PlanName name) {
        return planRepository.save(Plan.create(name, 10, 1000, 3, false, true, false, BigDecimal.valueOf(9900)));
    }

    // AdminUserRepository#search 의 has* 플래그(PG enum 파라미터 IS NULL 단독사용 회피용)를
    // 테스트 가독성을 위해 null 값 하나로 감춘 얇은 래퍼. companyId는 기본으로 companyA를 쓴다.
    private Page<AdminUserProjection> search(String keyword, Role role, UserStatus status, PlanName plan,
                                              Pageable pageable) {
        return search(companyA, keyword, role, status, plan, pageable);
    }

    private Page<AdminUserProjection> search(Long companyId, String keyword, Role role, UserStatus status,
                                              PlanName plan, Pageable pageable) {
        return adminUserRepository.search(
                companyId, keyword, role != null, role, status != null, status, plan != null, plan,
                plan == PlanName.FREE, UserPlanStatus.ACTIVE, pageable);
    }

    @Test
    void search_필터없으면_전체사용자_생성일역순() {
        User first = saveUser("김철수", "chulsoo@haja.com", Role.USER, UserStatus.ACTIVE);
        User second = saveUser("이영희", "younghee@haja.com", Role.ADMIN, UserStatus.ACTIVE);

        Page<AdminUserProjection> page = search(null, null, null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(AdminUserProjection::id)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void search_다른회사사용자는_결과에서_제외한다() {
        saveUser("같은회사", "same-company@haja.com", Role.USER, UserStatus.ACTIVE, companyA);
        saveUser("다른회사", "other-company@haja.com", Role.USER, UserStatus.ACTIVE, companyB);

        Page<AdminUserProjection> page = search(companyA, null, null, null, null, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(AdminUserProjection::email)
                .containsExactly("same-company@haja.com");
    }

    @Test
    void search_키워드는_이름과이메일_대소문자무시부분일치() {
        saveUser("박지민", "jimin.park@haja.com", Role.USER, UserStatus.ACTIVE);
        saveUser("최서준", "seojun@haja.com", Role.USER, UserStatus.ACTIVE);

        Page<AdminUserProjection> byName = search("%지민%", null, null, null, PageRequest.of(0, 10));
        Page<AdminUserProjection> byEmail = search("%jimin%", null, null, null, PageRequest.of(0, 10));

        assertThat(byName.getContent()).extracting(AdminUserProjection::email)
                .containsExactly("jimin.park@haja.com");
        assertThat(byEmail.getContent()).extracting(AdminUserProjection::email)
                .containsExactly("jimin.park@haja.com");
    }

    @Test
    void search_role과status_필터조합() {
        saveUser("관리자1", "admin1@haja.com", Role.ADMIN, UserStatus.ACTIVE);
        saveUser("정지된관리자", "admin2@haja.com", Role.ADMIN, UserStatus.SUSPENDED);
        saveUser("일반사용자", "user1@haja.com", Role.USER, UserStatus.ACTIVE);

        Page<AdminUserProjection> page = search(null, Role.ADMIN, UserStatus.ACTIVE, null, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(AdminUserProjection::email)
                .containsExactly("admin1@haja.com");
    }

    @Test
    void search_활성구독있으면_plan포함_없으면_null() {
        Plan plan = savePlan(PlanName.ENTERPRISE);
        User subscribed = saveUser("구독자", "subscribed@haja.com", Role.USER, UserStatus.ACTIVE);
        saveUser("무구독자", "nosub@haja.com", Role.USER, UserStatus.ACTIVE);
        userPlanRepository.save(UserPlan.forUser(subscribed.getId(), plan.getId()));

        Page<AdminUserProjection> page = search(null, null, null, null, PageRequest.of(0, 10));

        AdminUserProjection subscribedRow = page.getContent().stream()
                .filter(p -> p.id().equals(subscribed.getId())).findFirst().orElseThrow();
        AdminUserProjection unsubscribedRow = page.getContent().stream()
                .filter(p -> p.email().equals("nosub@haja.com")).findFirst().orElseThrow();

        assertThat(subscribedRow.plan()).isEqualTo(PlanName.ENTERPRISE);
        assertThat(unsubscribedRow.plan()).isNull();
    }

    @Test
    void search_plan필터는_만료구독을_제외한다() {
        Plan plan = savePlan(PlanName.STANDARD);
        User expired = saveUser("만료구독자", "expired@haja.com", Role.USER, UserStatus.ACTIVE);
        UserPlan userPlan = userPlanRepository.save(UserPlan.forUser(expired.getId(), plan.getId()));
        userPlan.requestUpgrade();
        userPlanRepository.flush();

        Page<AdminUserProjection> page = search(null, null, null, PlanName.STANDARD, PageRequest.of(0, 10));

        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void search_plan필터가_FREE면_활성구독없는_사용자도_포함한다() {
        Plan enterprise = savePlan(PlanName.ENTERPRISE);
        User subscribed = saveUser("구독자", "subscribed2@haja.com", Role.USER, UserStatus.ACTIVE);
        User nosub = saveUser("무구독자2", "nosub2@haja.com", Role.USER, UserStatus.ACTIVE);
        userPlanRepository.save(UserPlan.forUser(subscribed.getId(), enterprise.getId()));

        Page<AdminUserProjection> page = search(null, null, null, PlanName.FREE, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(AdminUserProjection::id)
                .containsExactly(nosub.getId());
    }

    @Test
    void search_페이징_totalElements와content크기() {
        for (int i = 0; i < 15; i++) {
            saveUser("사용자" + i, "user" + i + "@haja.com", Role.USER, UserStatus.ACTIVE);
        }

        Page<AdminUserProjection> firstPage = search(null, null, null, null, PageRequest.of(0, 10));

        assertThat(firstPage.getTotalElements()).isEqualTo(15);
        assertThat(firstPage.getContent()).hasSize(10);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
    }

    @Test
    void countByCompanyIdAndStatus_회사내_상태별_사용자수() {
        saveUser("활성1", "active1@haja.com", Role.USER, UserStatus.ACTIVE, companyA);
        saveUser("활성2", "active2@haja.com", Role.USER, UserStatus.ACTIVE, companyA);
        saveUser("정지1", "suspended1@haja.com", Role.USER, UserStatus.SUSPENDED, companyA);
        saveUser("다른회사활성", "other-active@haja.com", Role.USER, UserStatus.ACTIVE, companyB);

        assertThat(adminUserRepository.countByCompanyIdAndStatus(companyA, UserStatus.ACTIVE)).isEqualTo(2);
        assertThat(adminUserRepository.countByCompanyIdAndStatus(companyA, UserStatus.SUSPENDED)).isEqualTo(1);
        assertThat(adminUserRepository.countByCompanyIdAndStatus(companyB, UserStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    void countByCompanyIdAndCreatedAtBetween_회사내_기간내_가입자수() {
        saveUser("이번주가입", "thisweek@haja.com", Role.USER, UserStatus.ACTIVE, companyA);
        saveUser("다른회사가입", "other-thisweek@haja.com", Role.USER, UserStatus.ACTIVE, companyB);

        LocalDateTime from = LocalDateTime.now().minusDays(7);
        LocalDateTime to = LocalDateTime.now().plusMinutes(1);

        assertThat(adminUserRepository.countByCompanyIdAndCreatedAtBetween(companyA, from, to)).isEqualTo(1);
        assertThat(adminUserRepository.countByCompanyIdAndCreatedAtBetween(companyA, from.minusDays(30), from))
                .isEqualTo(0);
    }
}
