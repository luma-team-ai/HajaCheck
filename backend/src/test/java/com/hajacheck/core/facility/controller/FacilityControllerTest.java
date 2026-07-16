package com.hajacheck.core.facility.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.facility.dto.FacilityScheduleRequest;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
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
 * POST /api/facilities/{id}/schedule MVC 통합 테스트(dev-04-03, #268).
 * AuthControllerTest/MembershipControllerTest 와 동일하게 전역 시큐리티 필터체인이
 * ClientRegistrationRepository 를 요구해 @SpringBootTest+MockMvc(+PostgresTestSupport) 로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FacilityControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
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

    private Facility saveFacility(Long ownerId) {
        return facilityRepository.save(Facility.builder()
                .ownerId(ownerId)
                .name("테스트빌딩")
                .type("BUILDING")
                .address("서울시 강남구")
                .build());
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void 점검주기설정_본인시설_200_다음점검일산출() throws Exception {
        User owner = saveUser("owner@haja.com");
        Facility facility = saveFacility(owner.getId());
        FacilityScheduleRequest request = new FacilityScheduleRequest(6);

        mockMvc.perform(post("/api/facilities/{id}/schedule", facility.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.inspectionCycleMonths").value(6))
                .andExpect(jsonPath("$.data.nextInspectionDueAt")
                        .value(LocalDate.now().plusMonths(6).toString()));
    }

    @Test
    void 점검주기설정_없는시설_404_FACILITY_NOT_FOUND() throws Exception {
        User owner = saveUser("owner2@haja.com");
        FacilityScheduleRequest request = new FacilityScheduleRequest(6);

        mockMvc.perform(post("/api/facilities/{id}/schedule", 999999L)
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FACILITY_NOT_FOUND"));
    }

    @Test
    void 점검주기설정_타인소유시설_404_FACILITY_NOT_FOUND() throws Exception {
        User owner = saveUser("owner3@haja.com");
        User stranger = saveUser("stranger@haja.com");
        Facility facility = saveFacility(owner.getId());
        FacilityScheduleRequest request = new FacilityScheduleRequest(6);

        mockMvc.perform(post("/api/facilities/{id}/schedule", facility.getId())
                        .with(csrf()).with(authentication(authOf(stranger)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FACILITY_NOT_FOUND"));
    }

    @Test
    void 점검주기설정_유효성실패_0이하_400() throws Exception {
        User owner = saveUser("owner4@haja.com");
        Facility facility = saveFacility(owner.getId());
        FacilityScheduleRequest request = new FacilityScheduleRequest(0);

        mockMvc.perform(post("/api/facilities/{id}/schedule", facility.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 점검주기설정_미인증_401() throws Exception {
        FacilityScheduleRequest request = new FacilityScheduleRequest(6);

        mockMvc.perform(post("/api/facilities/{id}/schedule", 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}
