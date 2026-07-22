package com.hajacheck.admin.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 플랜·쿼터 관리 API(#507) MVC 통합 테스트 — 전역 시큐리티 필터체인(/api/admin/** → hasRole(ADMIN))과
 * 회사 스코프·상속·플랜 변경 이력을 실 PostgreSQL(Testcontainers)에서 함께 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminPlanControllerTest extends PostgresTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
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

    // ── 인가(ADMIN role) 경계 ──

    @Test
    void 플랜조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/admin/plan"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 플랜조회_일반사용자_403() throws Exception {
        User user = saveUser(Role.USER, null);
        mockMvc.perform(get("/api/admin/plan").with(authentication(authOf(user))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 카탈로그조회_일반사용자_403() throws Exception {
        User user = saveUser(Role.USER, null);
        mockMvc.perform(get("/api/admin/plans").with(authentication(authOf(user))))
                .andExpect(status().isForbidden());
    }

    // ── 카탈로그 ──

    @Test
    void 카탈로그조회_관리자_200_전체요금제() throws Exception {
        seedPlans();
        User admin = saveUser(Role.ADMIN, null);
        mockMvc.perform(get("/api/admin/plans").with(authentication(authOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.plans.length()").value(3));
    }

    // ── 회사 스코프 + 상속 ──

    @Test
    void 현재플랜조회_승인된회사관리자_200() throws Exception {
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.FREE);
        mockMvc.perform(get("/api/admin/plan").with(authentication(authOf(fx.admin()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.name").value("FREE"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.usage.analyzedImageCount").value(0));
    }

    @Test
    void 현재플랜조회_회사없는관리자_403() throws Exception {
        User admin = saveUser(Role.ADMIN, null);
        mockMvc.perform(get("/api/admin/plan").with(authentication(authOf(admin))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 현재플랜조회_미승인멤버십관리자_404_상속안됨() throws Exception {
        // 회사·플랜은 있으나 관리자 멤버십이 PENDING → 회사 플랜 상속 대상 아님(§2.6).
        Company company = saveApprovedCompany();
        User admin = saveUser(Role.ADMIN, company.getId());
        companyMembershipRepository.save(
                CompanyMembership.invite(company.getId(), admin.getId(), null, null));
        seedPlans();
        userPlanRepository.save(UserPlan.forCompany(company.getId(), planId(PlanName.FREE)));

        mockMvc.perform(get("/api/admin/plan").with(authentication(authOf(admin))))
                .andExpect(status().isNotFound());
    }

    // ── 플랜 변경 + 이력 ──

    @Test
    void 플랜변경_FREE에서STANDARD_200_이력보존() throws Exception {
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.FREE);

        mockMvc.perform(patch("/api/admin/plan")
                        .with(csrf()).with(authentication(authOf(fx.admin())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"STANDARD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.name").value("STANDARD"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // 변경 이력: 최신순 = STANDARD(ACTIVE) → FREE(EXPIRED)
        mockMvc.perform(get("/api/admin/plan/history").with(authentication(authOf(fx.admin()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history.length()").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.history[0].planName").value("STANDARD"))
                .andExpect(jsonPath("$.data.history[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.history[0].endedAt").doesNotExist())
                .andExpect(jsonPath("$.data.history[1].planName").value("FREE"))
                .andExpect(jsonPath("$.data.history[1].status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.history[1].endedAt").exists());
    }

    @Test
    void 플랜변경이력조회_size로상한_totalElements는전체건수() throws Exception {
        // PR#525 머신 리뷰 P3: 이력이 페이지 크기를 초과해도 content 는 상한만, totalElements 는 전체 수.
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.FREE);
        mockMvc.perform(patch("/api/admin/plan")
                        .with(csrf()).with(authentication(authOf(fx.admin())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"STANDARD\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/admin/plan")
                        .with(csrf()).with(authentication(authOf(fx.admin())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"ENTERPRISE\"}"))
                .andExpect(status().isOk());
        // 이 시점 이력 3건(FREE→STANDARD→ENTERPRISE). size=2 로 조회하면 content 는 2건, totalElements 는 3.

        mockMvc.perform(get("/api/admin/plan/history")
                        .param("size", "2")
                        .with(authentication(authOf(fx.admin()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history.length()").value(2))
                .andExpect(jsonPath("$.data.history[0].planName").value("ENTERPRISE"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    void 플랜변경_동일요금제_200_멱등_이력추가없음() throws Exception {
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.STANDARD);

        mockMvc.perform(patch("/api/admin/plan")
                        .with(csrf()).with(authentication(authOf(fx.admin())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"STANDARD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.name").value("STANDARD"));

        // 동일 요금제 재지정은 no-op → 이력은 여전히 1건(초기 ACTIVE)만.
        mockMvc.perform(get("/api/admin/plan/history").with(authentication(authOf(fx.admin()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history.length()").value(1));
    }

    @Test
    void 플랜변경_일반사용자_403() throws Exception {
        User user = saveUser(Role.USER, null);
        mockMvc.perform(patch("/api/admin/plan")
                        .with(csrf()).with(authentication(authOf(user)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"STANDARD\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 플랜변경_비소유자ADMIN멤버_403_결제게이트우회차단() throws Exception {
        // PR#525 머신 리뷰 P1: 승인된 멤버십만으로는 changePlan(즉시 ACTIVE 발급, 무결제)을 허용해선
        // 안 된다 — requestUpgrade 와 동일하게 회사 소유자(owner)만 허용해야 한다.
        Company company = saveApprovedCompany();
        User nonOwnerAdmin = saveUser(Role.ADMIN, company.getId());
        CompanyMembership membership = CompanyMembership.invite(
                company.getId(), nonOwnerAdmin.getId(), company.getOwnerUserId(), null);
        membership.approve();
        companyMembershipRepository.save(membership);
        seedPlans();
        userPlanRepository.save(UserPlan.forCompany(company.getId(), planId(PlanName.FREE)));

        mockMvc.perform(patch("/api/admin/plan")
                        .with(csrf()).with(authentication(authOf(nonOwnerAdmin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"ENTERPRISE\"}"))
                .andExpect(status().isForbidden());
    }

    // ── 회사 멤버별 쿼터 목록(#525 팔로우업 — PR머신 P2: 이 엔드포인트가 테스트에서 전혀 검증되지 않았음) ──

    @Test
    void 플랜쿼터목록조회_활성구독있음_멤버별플랜값과stats() throws Exception {
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.STANDARD);
        User member = saveUser(Role.USER, fx.company().getId());
        // HajaCheck_script.sql(#517/HAJA-308)이 신규 설치 시드를 이미 심어두므로 seedPlans()는
        // no-op이다 — 값을 하드코딩하면 시드가 바뀔 때마다 깨지니 실제 값을 조회해 비교한다.
        Integer standardQuotaLimit = planRepository.findByName(PlanName.STANDARD)
                .orElseThrow().getMaxMonthlyAnalyses();

        mockMvc.perform(get("/api/admin/plan-quota").with(authentication(authOf(fx.admin()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].plan").value("STANDARD"))
                .andExpect(jsonPath("$.data.content[0].quotaLimit").value(standardQuotaLimit))
                .andExpect(jsonPath("$.data.content[1].email").value(member.getEmail()))
                .andExpect(jsonPath("$.data.content[1].plan").value("STANDARD"))
                .andExpect(jsonPath("$.data.content[1].quotaLimit").value(standardQuotaLimit))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.stats.activeUsers").value(2))
                .andExpect(jsonPath("$.data.stats.companyPlan").value("STANDARD"));
    }

    @Test
    void 플랜쿼터목록조회_활성구독없음_plan과quotaLimit이null이어도200() throws Exception {
        // 회사·멤버십은 유효하지만 user_plans 자체가 없는 상태(getCurrentPlan과 달리 404로 실패시키지 않는다).
        Company company = saveApprovedCompany();
        User admin = saveUser(Role.ADMIN, company.getId());
        companyMembershipRepository.save(CompanyMembership.approvedOwner(company.getId(), admin.getId()));

        mockMvc.perform(get("/api/admin/plan-quota").with(authentication(authOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].plan").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].quotaLimit").doesNotExist())
                .andExpect(jsonPath("$.data.stats.companyPlan").doesNotExist());
    }

    @Test
    void 플랜쿼터목록조회_keyword로_이름검색() throws Exception {
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.FREE);
        User target = userRepository.save(User.builder()
                .email("target-member@haja.com")
                .name("검색대상")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .companyId(fx.company().getId())
                .status(UserStatus.ACTIVE)
                .build());

        mockMvc.perform(get("/api/admin/plan-quota")
                        .param("keyword", "검색대상")
                        .with(authentication(authOf(fx.admin()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value(target.getEmail()))
                // stats는 검색어와 무관하게 회사 전체 기준(관리자 포함 2명)이어야 한다.
                .andExpect(jsonPath("$.data.stats.activeUsers").value(2));
    }

    @Test
    void 플랜쿼터목록조회_size0이면400() throws Exception {
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.FREE);
        mockMvc.perform(get("/api/admin/plan-quota")
                        .param("size", "0")
                        .with(authentication(authOf(fx.admin()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 플랜쿼터목록조회_일반사용자_403() throws Exception {
        User user = saveUser(Role.USER, null);
        mockMvc.perform(get("/api/admin/plan-quota").with(authentication(authOf(user))))
                .andExpect(status().isForbidden());
    }

    // ── 픽스처 헬퍼 ──

    private record Fixture(User admin, Company company) {
    }

    // fx.admin() 은 changePlan(owner 한정, PR#525 P1 픽스) 테스트에도 재사용되므로 실제 company.ownerUserId
    // 와 동일한 사용자로 만든다 — owner 아닌 승인 멤버는 별도 픽스처(플랜변경_비소유자ADMIN멤버_403...)로 다룬다.
    private Fixture approvedCompanyAdminWithPlan(PlanName planName) {
        int n = SEQ.incrementAndGet();
        User admin = saveUser(Role.ADMIN, null);
        Company company = Company.createPendingReview(
                admin.getId(), "회사" + n, "BRN-507-" + n, "대표", "서울", null,
                "https://files.example/brn.pdf", "{\"source\":\"MANUAL_INPUT\"}");
        company.markBusinessVerified();
        company.approve(admin.getId());
        company = companyRepository.save(company);
        admin.assignToCompany(company.getId());
        admin = userRepository.save(admin);
        companyMembershipRepository.save(
                CompanyMembership.approvedOwner(company.getId(), admin.getId()));
        seedPlans();
        userPlanRepository.save(UserPlan.forCompany(company.getId(), planId(planName)));
        return new Fixture(admin, company);
    }

    private Company saveApprovedCompany() {
        int n = SEQ.incrementAndGet();
        User owner = saveUser(Role.ADMIN, null);
        Company company = Company.createPendingReview(
                owner.getId(), "회사" + n, "BRN-507-" + n, "대표", "서울", null,
                "https://files.example/brn.pdf", "{\"source\":\"MANUAL_INPUT\"}");
        company.markBusinessVerified();
        company.approve(owner.getId());
        return companyRepository.save(company);
    }

    private User saveUser(Role role, Long companyId) {
        int n = SEQ.incrementAndGet();
        return userRepository.save(User.builder()
                .email("admin-plan-" + n + "@haja.com")
                .name("관리자" + n)
                .role(role)
                .passwordHash("$2a$10$hashed")
                .companyId(companyId)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private void seedPlans() {
        if (planRepository.findByName(PlanName.FREE).isPresent()) {
            return;
        }
        planRepository.save(Plan.create(PlanName.FREE, 1, 30, 1, true, false, false, BigDecimal.ZERO));
        planRepository.save(Plan.create(
                PlanName.STANDARD, 10, 300, 5, false, true, true, new BigDecimal("29000.00")));
        planRepository.save(Plan.create(
                PlanName.ENTERPRISE, null, null, 50, false, true, true, new BigDecimal("99000.00")));
    }

    private Long planId(PlanName name) {
        return planRepository.findByName(name).orElseThrow().getId();
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
