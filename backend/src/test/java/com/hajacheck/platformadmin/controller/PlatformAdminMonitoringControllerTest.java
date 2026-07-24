package com.hajacheck.platformadmin.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.support.PostgresTestSupport;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플랫폼 관리자 콘솔 — 시스템 모니터링 API(#728) MVC 통합 테스트. PlatformAdminStatsControllerTest 와 동일 패턴
 * (@SpringBootTest+MockMvc — 전역 시큐리티 필터체인의 "/api/platform-admin/**" hasRole(PLATFORM_ADMIN)를
 * 실제로 태워야 함). ai-server 는 테스트 환경에 떠 있지 않으므로 헬스체크는 연결 실패(DOWN)로 응답한다 —
 * 이 테스트는 "필드 구조가 계약대로 채워지는지"만 검증하고 특정 상태값을 단정하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PlatformAdminMonitoringControllerTest extends PostgresTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;

    @Test
    void 조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/platform-admin/monitoring"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 조회_회사ADMIN이면_403() throws Exception {
        User companyAdmin = saveUser(Role.ADMIN, 1L);
        mockMvc.perform(get("/api/platform-admin/monitoring").with(authentication(authOf(companyAdmin))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 조회_PLATFORM_ADMIN이면_200이고_계약필드를모두반환한다() throws Exception {
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);

        mockMvc.perform(get("/api/platform-admin/monitoring").with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.serverHealth.length()").value(3))
                .andExpect(jsonPath("$.data.serverHealth[0].id").value("api-server"))
                .andExpect(jsonPath("$.data.serverHealth[0].status").value("HEALTHY"))
                .andExpect(jsonPath("$.data.jobQueue.summary.inProgress").value(0))
                .andExpect(jsonPath("$.data.jobQueue.jobs").isArray())
                .andExpect(jsonPath("$.data.jobQueue.jobs.length()").value(0))
                .andExpect(jsonPath("$.data.resourceUsage.cpuUsagePercent").isNumber())
                .andExpect(jsonPath("$.data.resourceUsage.memoryUsagePercent").isNumber())
                .andExpect(jsonPath("$.data.resourceUsage.diskUsagePercent").isNumber())
                .andExpect(jsonPath("$.data.errorLogs").isArray());
    }

    // Actuator 과노출 회귀 테스트(#728 리뷰 지적) — health 를 제외한 나머지는 PLATFORM_ADMIN 전용이어야 한다.
    @Test
    void actuator_metrics는_회사ADMIN이면_403() throws Exception {
        User companyAdmin = saveUser(Role.ADMIN, 1L);
        mockMvc.perform(get("/actuator/metrics").with(authentication(authOf(companyAdmin))))
                .andExpect(status().isForbidden());
    }

    @Test
    void actuator_health는_무인증도_200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    private User saveUser(Role role, Long companyId) {
        int n = SEQ.incrementAndGet();
        return userRepository.save(User.builder()
                .email("monitoring-" + n + "@haja.com")
                .name("사용자" + n)
                .role(role)
                .passwordHash("$2a$10$hashed")
                .companyId(companyId)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
