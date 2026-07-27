package com.hajacheck.core.defect.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.hajacheck.core.defect.dto.NlSearchAiEnvelope;
import com.hajacheck.core.defect.dto.NlSearchFilters;
import com.hajacheck.core.defect.dto.NlSearchResult;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * /api/defects/nl-search MVC·시큐리티 통합 테스트(HAJA-120, HAJA-509). 실제 NlSearchService와
 * PostgreSQL 멤버십·플랜 조회 경계를 사용하고 외부 FastAPI RestClient만 대체한다. HTTP 직렬화와
 * 상세 게이트 실패 조합은 NlSearchServiceTest가 담당한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DefectSearchControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private CompanyMembershipRepository companyMembershipRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private UserPlanRepository userPlanRepository;

    @MockBean(name = "aiServerRestClient")
    private RestClient aiServerRestClient;

    private LoginUser inspectorUser;
    private LoginUser adminUser;

    private static final AtomicLong BUSINESS_NUMBER_SEQUENCE = new AtomicLong(8_000_000_000L);
    private static final String REQUEST_BODY = """
            {"query":"D등급 이상 조치 대기 하자"}
            """;

    @BeforeEach
    void setUp() {
        reset(aiServerRestClient);
        inspectorUser = new LoginUser(saveCompanyUserWithPlan(
                "inspector-nlsearch@haja.com", "점검자", Role.INSPECTOR, PlanName.STANDARD));
        adminUser = new LoginUser(saveCompanyUserWithPlan(
                "admin-nlsearch@haja.com", "회사관리자", Role.ADMIN, PlanName.STANDARD));
    }

    @Test
    void 자연어검색_미인증_401() throws Exception {
        mockMvc.perform(post("/api/defects/nl-search").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"USER", "COUNSELOR", "PLATFORM_ADMIN"})
    void 자연어검색_허용되지않은역할_403_FORBIDDEN(Role role) throws Exception {
        User blockedUser = userRepository.save(User.builder()
                .email(role.name().toLowerCase() + "-nlsearch@haja.com")
                .name("비허용사용자")
                .role(role)
                .passwordHash(passwordEncoder.encode("pw123456"))
                .status(UserStatus.ACTIVE)
                .build());
        LoginUser loginUser = new LoginUser(blockedUser);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                loginUser, null, loginUser.getAuthorities());

        mockMvc.perform(post("/api/defects/nl-search").with(csrf()).with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        verifyNoInteractions(aiServerRestClient);
    }

    @Test
    void 자연어검색_점검자_게이트통과_200과필터반환() throws Exception {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                inspectorUser, null, inspectorUser.getAuthorities());
        givenAiSearchSuccess();

        mockMvc.perform(post("/api/defects/nl-search").with(csrf()).with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.filters.grade[0]").value("D"))
                .andExpect(jsonPath("$.data.filters.status[0]").value("CONFIRMED"));
    }

    @Test
    void 자연어검색_회사관리자_게이트통과_200과필터반환() throws Exception {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                adminUser, null, adminUser.getAuthorities());
        givenAiSearchSuccess();

        mockMvc.perform(post("/api/defects/nl-search").with(csrf()).with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.filters.grade[0]").value("D"))
                .andExpect(jsonPath("$.data.filters.status[0]").value("CONFIRMED"));
    }

    @Test
    void 자연어검색_회사관리자_AI부가기능없음_403_AI_ADDON_REQUIRED() throws Exception {
        LoginUser noAddonAdmin = new LoginUser(saveCompanyUserWithPlan(
                "admin-no-addon-nlsearch@haja.com", "AI미보유관리자", Role.ADMIN, PlanName.FREE));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                noAddonAdmin, null, noAddonAdmin.getAuthorities());

        mockMvc.perform(post("/api/defects/nl-search").with(csrf()).with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AI_ADDON_REQUIRED"));

        verifyNoInteractions(aiServerRestClient);
    }

    @Test
    void 자연어검색_점검자_빈질의_400_INVALID_INPUT() throws Exception {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                inspectorUser, null, inspectorUser.getAuthorities());

        mockMvc.perform(post("/api/defects/nl-search").with(csrf()).with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        verifyNoInteractions(aiServerRestClient);
    }

    private User saveCompanyUserWithPlan(String email, String name, Role role, PlanName planName) {
        User user = userRepository.save(User.builder()
                .email(email)
                .name(name)
                .role(role)
                .passwordHash(passwordEncoder.encode("pw123456"))
                .status(UserStatus.ACTIVE)
                .build());

        long businessNumber = BUSINESS_NUMBER_SEQUENCE.getAndIncrement();
        Company company = companyRepository.save(Company.createPendingReview(
                user.getId(), "자연어검색테스트회사", String.valueOf(businessNumber), "김대표",
                "서울시 강남구", null, "http://files/brn.png", "{}"));
        user.assignToCompany(company.getId());
        userRepository.save(user);
        companyMembershipRepository.save(CompanyMembership.approvedOwner(company.getId(), user.getId()));
        company.markBusinessVerified();
        company.approve(user.getId());
        companyRepository.save(company);

        Plan plan = planRepository.findByName(planName).orElseThrow();
        userPlanRepository.save(UserPlan.forCompany(company.getId(), plan.getId()));
        return user;
    }

    private void givenAiSearchSuccess() {
        NlSearchResult result = new NlSearchResult(
                new NlSearchFilters(List.of(), List.of("D", "E"), List.of("CONFIRMED"), null),
                List.of(), null, 0.92);
        NlSearchAiEnvelope envelope = new NlSearchAiEnvelope(true, result, null);
        RestClient.RequestBodyUriSpec requestSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(aiServerRestClient.post()).thenReturn(requestSpec);
        when(requestSpec.uri("/ai/nl-search")).thenReturn(requestSpec);
        when(requestSpec.headers(any())).thenReturn(requestSpec);
        when(requestSpec.body(ArgumentMatchers.any(Object.class))).thenReturn(requestSpec);
        when(requestSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(NlSearchAiEnvelope.class)).thenReturn(envelope);
    }
}
