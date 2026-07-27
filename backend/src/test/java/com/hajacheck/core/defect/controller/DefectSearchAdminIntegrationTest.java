package com.hajacheck.core.defect.controller;

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
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.support.PostgresTestSupport;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN 자연어 검색 결합 회귀 테스트(HAJA-509) — 실제 SecurityConfig, NlSearchService와 PostgreSQL
 * 멤버십·플랜 조회 경계를 통과시키고, 외부 FastAPI 응답만 로컬 HTTP 스텁으로 대체한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DefectSearchAdminIntegrationTest extends PostgresTestSupport {

    private static final AtomicLong BUSINESS_NUMBER_SEQUENCE = new AtomicLong(8_000_000_000L);
    private static final HttpServer AI_SERVER = createAiServer();

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

    @DynamicPropertySource
    static void aiServerProperties(DynamicPropertyRegistry registry) {
        registry.add("ai.server.base-url",
                () -> "http://127.0.0.1:" + AI_SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopAiServer() {
        AI_SERVER.stop(0);
    }

    @Test
    void 자연어검색_회사관리자_유효회사멤버십_AI플랜_실제서비스경계통과_200() throws Exception {
        long businessNumber = BUSINESS_NUMBER_SEQUENCE.getAndIncrement();
        User admin = userRepository.save(User.builder()
                .email("admin-nlsearch-integration@haja.com")
                .name("회사관리자")
                .role(Role.ADMIN)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());

        Company company = companyRepository.save(Company.createPendingReview(
                admin.getId(), "자연어검색테스트회사", String.valueOf(businessNumber), "김대표",
                "서울시 강남구", null, "http://files/brn.png", "{}"));
        admin.assignToCompany(company.getId());
        userRepository.save(admin);
        companyMembershipRepository.save(CompanyMembership.approvedOwner(company.getId(), admin.getId()));
        company.markBusinessVerified();
        company.approve(admin.getId());
        companyRepository.save(company);

        Plan addonPlan = planRepository.findByName(PlanName.STANDARD).orElseThrow();
        userPlanRepository.save(UserPlan.forCompany(company.getId(), addonPlan.getId()));

        LoginUser principal = new LoginUser(admin);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        mockMvc.perform(post("/api/defects/nl-search").with(csrf()).with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"D등급 이상 하자"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.filters.grade[0]").value("D"))
                .andExpect(jsonPath("$.data.filters.grade[1]").value("E"));
    }

    private static HttpServer createAiServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/ai/nl-search", exchange -> {
                byte[] response = """
                        {"success":true,"data":{"filters":{"type":[],"grade":["D","E"],"status":[],\
                        "confidenceMin":null},"unsupported_terms":[],"clarifying_question":null,\
                        "interpretation_confidence":0.9}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("테스트 AI HTTP 서버를 시작할 수 없습니다.", e);
        }
    }
}
