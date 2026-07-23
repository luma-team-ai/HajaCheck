package com.hajacheck.platformadmin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.util.List;
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
 * 플랫폼 관리자 콘솔 — 플랜 정책 설정 API(#624 후속) MVC 통합 테스트. 전역 시큐리티 필터체인
 * ("/api/platform-admin/**" → hasRole(PLATFORM_ADMIN))과 3플랜 일괄 교체 계약을 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PlatformAdminPlanPolicyControllerTest extends PostgresTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 카탈로그조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/platform-admin/plans"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 카탈로그조회_회사ADMIN이면_403() throws Exception {
        User companyAdmin = saveCompanyAdmin();
        mockMvc.perform(get("/api/platform-admin/plans").with(authentication(authOf(companyAdmin))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 카탈로그조회_플랫폼관리자_200_전체3개플랜() throws Exception {
        seedPlans();
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        mockMvc.perform(get("/api/platform-admin/plans").with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.plans.length()").value(3));
    }

    @Test
    void 정책변경_플랫폼관리자_200_값이실제로반영된다() throws Exception {
        seedPlans();
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        String body = objectMapper.writeValueAsString(updateRequestBody(
                entry("FREE", "0", 1, 30, 1, true, false),
                entry("STANDARD", "39000", 20, 2000, 10, false, true),
                entry("ENTERPRISE", "250000", null, null, null, false, true)));

        mockMvc.perform(put("/api/platform-admin/plans")
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plans[?(@.name=='STANDARD')].priceMonthly")
                        .value(List.of(39000.00)))
                .andExpect(jsonPath("$.data.plans[?(@.name=='STANDARD')].maxFacilities")
                        .value(List.of(20)));

        Plan standard = planRepository.findByName(PlanName.STANDARD).orElseThrow();
        assertThat(standard.getPriceMonthly()).isEqualByComparingTo(new BigDecimal("39000"));
        assertThat(standard.getMaxFacilities()).isEqualTo(20);
        assertThat(standard.getMaxMonthlyAnalyses()).isEqualTo(2000);
        assertThat(standard.isHasCounselorAccess()).isTrue();
    }

    @Test
    void 정책변경_hasAiAddon은_요청에없어도_기존값유지() throws Exception {
        seedPlans();
        Plan standard = planRepository.findByName(PlanName.STANDARD).orElseThrow();
        boolean originalAiAddon = standard.isHasAiAddon();
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        String body = objectMapper.writeValueAsString(updateRequestBody(
                entry("FREE", "0", 1, 30, 1, true, false),
                entry("STANDARD", "39000", 20, 2000, 10, false, true),
                entry("ENTERPRISE", "250000", null, null, null, false, true)));

        mockMvc.perform(put("/api/platform-admin/plans")
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        Plan reloaded = planRepository.findByName(PlanName.STANDARD).orElseThrow();
        assertThat(reloaded.isHasAiAddon()).isEqualTo(originalAiAddon);
    }

    @Test
    void 정책변경_플랜하나가누락되면_400() throws Exception {
        seedPlans();
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        // ENTERPRISE 누락 — 2건만 전송.
        String body = objectMapper.writeValueAsString(updateRequestBody(
                entry("FREE", "0", 1, 30, 1, true, false),
                entry("STANDARD", "39000", 20, 2000, 10, false, true)));

        mockMvc.perform(put("/api/platform-admin/plans")
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PLAN_POLICY_INVALID"));
    }

    @Test
    void 정책변경_플랜명이중복되면_400() throws Exception {
        seedPlans();
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        String body = objectMapper.writeValueAsString(updateRequestBody(
                entry("FREE", "0", 1, 30, 1, true, false),
                entry("FREE", "0", 1, 30, 1, true, false),
                entry("ENTERPRISE", "250000", null, null, null, false, true)));

        mockMvc.perform(put("/api/platform-admin/plans")
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PLAN_POLICY_INVALID"));
    }

    @Test
    void 정책변경_음수가격이면_400() throws Exception {
        seedPlans();
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        String body = objectMapper.writeValueAsString(updateRequestBody(
                entry("FREE", "-1", 1, 30, 1, true, false),
                entry("STANDARD", "39000", 20, 2000, 10, false, true),
                entry("ENTERPRISE", "250000", null, null, null, false, true)));

        mockMvc.perform(put("/api/platform-admin/plans")
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 정책변경_회사ADMIN이면_403() throws Exception {
        seedPlans();
        User companyAdmin = saveCompanyAdmin();
        String body = objectMapper.writeValueAsString(updateRequestBody(
                entry("FREE", "0", 1, 30, 1, true, false),
                entry("STANDARD", "39000", 20, 2000, 10, false, true),
                entry("ENTERPRISE", "250000", null, null, null, false, true)));

        mockMvc.perform(put("/api/platform-admin/plans")
                        .with(authentication(authOf(companyAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    private java.util.Map<String, Object> entry(String name, String priceMonthly, Integer maxFacilities,
            Integer maxMonthlyAnalyses, Integer maxSeats, boolean hasPdfWatermark, boolean hasCounselorAccess) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("name", name);
        map.put("priceMonthly", priceMonthly);
        map.put("maxFacilities", maxFacilities);
        map.put("maxMonthlyAnalyses", maxMonthlyAnalyses);
        map.put("maxSeats", maxSeats);
        map.put("hasPdfWatermark", hasPdfWatermark);
        map.put("hasCounselorAccess", hasCounselorAccess);
        return map;
    }

    @SafeVarargs
    private java.util.Map<String, Object> updateRequestBody(java.util.Map<String, Object>... entries) {
        return java.util.Map.of("plans", List.of(entries));
    }

    private User saveUser(Role role, Long companyId) {
        int n = SEQ.incrementAndGet();
        return userRepository.save(User.builder()
                .email("pp-" + n + "@haja.com")
                .name("사용자" + n)
                .role(role)
                .passwordHash("$2a$10$hashed")
                .companyId(companyId)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private User saveCompanyAdmin() {
        int n = SEQ.incrementAndGet();
        User owner = saveUser(Role.ADMIN, null);
        Company company = Company.createPendingReview(
                owner.getId(), "회사" + n, "BRN-624PP-" + n, "대표", "서울", null,
                "https://files.example/brn.pdf", "{\"source\":\"MANUAL_INPUT\"}");
        company.markBusinessVerified();
        company.approve(owner.getId());
        company = companyRepository.save(company);
        owner.assignToCompany(company.getId());
        return userRepository.save(owner);
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

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
