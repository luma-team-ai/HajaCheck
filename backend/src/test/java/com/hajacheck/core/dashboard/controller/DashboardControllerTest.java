package com.hajacheck.core.dashboard.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /api/dashboard/upcoming-inspections MVC 통합 테스트(dev-03-02).
 * FacilityControllerTest 와 동일하게 전역 시큐리티 필터체인 때문에
 * @SpringBootTest+MockMvc(+PostgresTestSupport) 로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DashboardControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FacilityRepository facilityRepository;

    private User saveUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .name("기업사용자")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
    }

    private void saveFacilityWithDueAt(Long ownerId, String name, LocalDate nextInspectionDueAt) {
        facilityRepository.save(Facility.builder()
                .ownerId(ownerId)
                .name(name)
                .type("BUILDING")
                .inspectionCycleMonths(6)
                .nextInspectionDueAt(nextInspectionDueAt)
                .build());
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void 다가오는점검조회_본인시설_200_오름차순() throws Exception {
        User owner = saveUser("owner-upcoming1@haja.com");
        LocalDate today = LocalDate.now();
        saveFacilityWithDueAt(owner.getId(), "10일후시설", today.plusDays(10));
        saveFacilityWithDueAt(owner.getId(), "3일후시설", today.plusDays(3));

        mockMvc.perform(get("/api/dashboard/upcoming-inspections")
                        .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].facilityName").value("3일후시설"))
                .andExpect(jsonPath("$.data[1].facilityName").value("10일후시설"));
    }

    @Test
    void 다가오는점검조회_소유시설없으면_200_빈배열() throws Exception {
        User owner = saveUser("owner-upcoming2@haja.com");

        mockMvc.perform(get("/api/dashboard/upcoming-inspections")
                        .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void 다가오는점검조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/dashboard/upcoming-inspections"))
                .andExpect(status().isUnauthorized());
    }
}
