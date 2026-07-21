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
        mockMvc.perform(get("/api/admin/plan").with(authentication(authOf(fx.admin))))
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
                        .with(csrf()).with(authentication(authOf(fx.admin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"STANDARD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.name").value("STANDARD"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // 변경 이력: 최신순 = STANDARD(ACTIVE) → FREE(EXPIRED)
        mockMvc.perform(get("/api/admin/plan/history").with(authentication(authOf(fx.admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history.length()").value(2))
                .andExpect(jsonPath("$.data.history[0].planName").value("STANDARD"))
                .andExpect(jsonPath("$.data.history[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.history[0].endedAt").doesNotExist())
                .andExpect(jsonPath("$.data.history[1].planName").value("FREE"))
                .andExpect(jsonPath("$.data.history[1].status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.history[1].endedAt").exists());
    }

    @Test
    void 플랜변경_동일요금제_200_멱등_이력추가없음() throws Exception {
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.STANDARD);

        mockMvc.perform(patch("/api/admin/plan")
                        .with(csrf()).with(authentication(authOf(fx.admin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"STANDARD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.name").value("STANDARD"));

        // 동일 요금제 재지정은 no-op → 이력은 여전히 1건(초기 ACTIVE)만.
        mockMvc.perform(get("/api/admin/plan/history").with(authentication(authOf(fx.admin))))
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

    // ── 픽스처 헬퍼 ──

    private record Fixture(User admin, Company company) {
    }

    private Fixture approvedCompanyAdminWithPlan(PlanName planName) {
        Company company = saveApprovedCompany();
        User admin = saveUser(Role.ADMIN, company.getId());
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
