package com.hajacheck.core.facility.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.facility.dto.FacilityCreateRequest;
import com.hajacheck.core.facility.dto.FacilityScheduleRequest;
import com.hajacheck.core.facility.dto.FacilityUpdateRequest;
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
    void 점검주기설정_유효성실패_상한초과_400() throws Exception {
        // @Max(120) 상한 방어(PR #284 P2): 극단값(Integer.MAX_VALUE)이 검증을 통과하면
        // Facility.updateSchedule 의 plusMonths 에서 산술 오버플로우로 500이 날 수 있으므로,
        // 상한 초과 요청은 검증 계층에서 400 으로 걸러져야 한다.
        User owner = saveUser("owner5@haja.com");
        Facility facility = saveFacility(owner.getId());
        FacilityScheduleRequest request = new FacilityScheduleRequest(Integer.MAX_VALUE);

        mockMvc.perform(post("/api/facilities/{id}/schedule", facility.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ── 시설물 등록/수정 입력값 범위 검증(#351) ──
    // BuiltYearValidatorTest 는 밸리데이터를 직접 호출할 뿐이라 @ValidBuiltYear 가 DTO 에 실제로
    // 걸려 400 이 나오는지는 검증하지 못한다. @Valid 누락 같은 회귀를 여기서 잡는다
    // (위 점검주기설정_유효성실패_상한초과_400 과 동일 취지).

    @Test
    void 시설물등록_유효성실패_준공년도_미래_400() throws Exception {
        User owner = saveUser("owner6@haja.com");
        FacilityCreateRequest request = createRequestWith(999999, 6);

        mockMvc.perform(post("/api/facilities")
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 시설물등록_유효성실패_준공년도_1900미만_400() throws Exception {
        User owner = saveUser("owner7@haja.com");
        FacilityCreateRequest request = createRequestWith(-100, 6);

        mockMvc.perform(post("/api/facilities")
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 시설물등록_유효성실패_점검주기_상한초과_400() throws Exception {
        User owner = saveUser("owner8@haja.com");
        FacilityCreateRequest request = createRequestWith(2008, 200);

        mockMvc.perform(post("/api/facilities")
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 시설물등록_경계값_준공년도1900_점검주기0_201() throws Exception {
        // 제약이 과해 정상 입력을 막지 않는지 — 하한 1900 과 "주기 미설정"(0)은 통과해야 한다.
        User owner = saveUser("owner9@haja.com");
        FacilityCreateRequest request = createRequestWith(1900, 0);

        mockMvc.perform(post("/api/facilities")
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void 시설물수정_유효성실패_준공년도_미래_400() throws Exception {
        // PUT 은 전체 교체라 등록과 동일 제약이어야 한다(FacilityUpdateRequest).
        User owner = saveUser("owner10@haja.com");
        Facility facility = saveFacility(owner.getId());
        FacilityUpdateRequest request = new FacilityUpdateRequest(
                "수정빌딩", "BUILDING", null, null, null, 999999, null, 6, null);

        mockMvc.perform(put("/api/facilities/{id}", facility.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    private FacilityCreateRequest createRequestWith(Integer builtYear, Integer inspectionCycleMonths) {
        return new FacilityCreateRequest(
                "검증빌딩", "BUILDING", null, null, null, builtYear, null, inspectionCycleMonths, null);
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
