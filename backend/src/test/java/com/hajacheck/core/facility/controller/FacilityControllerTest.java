package com.hajacheck.core.facility.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.facility.dto.FacilityCreateRequest;
import com.hajacheck.core.facility.dto.FacilityScheduleRequest;
import com.hajacheck.core.facility.dto.FacilityUpdateRequest;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.entity.FacilityInitialGrade;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
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
    private CompanyRepository companyRepository;
    @Autowired
    private CompanyMembershipRepository companyMembershipRepository;
    @Autowired
    private FacilityRepository facilityRepository;
    @Autowired
    private InspectionRepository inspectionRepository;

    private User saveUser(String email) {
        User user = userRepository.saveAndFlush(User.builder()
                .email(email)
                .name("기업사용자")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
        Company company = companyRepository.saveAndFlush(Company.createPendingReview(
                user.getId(), email + " 회사", "TEST-" + user.getId(), "대표자",
                "서울", null, "https://example.com/brn", "{\"source\":\"TEST\"}"));
        company.markBusinessVerified();
        company.approve(user.getId());
        companyRepository.saveAndFlush(company);
        companyMembershipRepository.saveAndFlush(
                CompanyMembership.approvedOwner(company.getId(), user.getId()));
        user.assignToCompany(company.getId());
        return userRepository.saveAndFlush(user);
    }

    private Facility saveFacility(Long ownerId) {
        return facilityRepository.save(Facility.builder()
                .companyId(userRepository.findById(ownerId).orElseThrow().getCompanyId())
                .name("테스트빌딩")
                .type("BUILDING")
                .address("서울시 강남구")
                .build());
    }

    // 시설물 현황 목록(#540 ⑥, HAJA-378) — 회사 소속 배정 가능 담당자(INSPECTOR/ADMIN + APPROVED 멤버십)를 시드한다.
    // inspections.assigned_inspector_id 는 DB 트리거(trg_inspections_check_assigned_inspector_company)로
    // 승인된 회사 소속 INSPECTOR/ADMIN 만 허용되므로 Role.USER 인 owner 를 그대로 재사용할 수 없다.
    private User saveInspector(String email, Long companyId) {
        User inspector = userRepository.saveAndFlush(User.builder()
                .email(email).name("점검자")
                .role(Role.INSPECTOR).passwordHash("$2a$10$hashed")
                .companyId(companyId).status(UserStatus.ACTIVE).build());
        companyMembershipRepository.saveAndFlush(CompanyMembership.approvedOwner(companyId, inspector.getId()));
        return inspector;
    }

    private Inspection saveInspection(Long facilityId, Long createdBy, Long assignedInspectorId,
                                       int roundNo, LocalDate inspectionDate) {
        return inspectionRepository.save(Inspection.builder()
                .facilityId(facilityId)
                .createdBy(createdBy)
                .assignedInspectorId(assignedInspectorId)
                .roundNo(roundNo)
                .inspectionDate(inspectionDate)
                .status(InspectionStatus.CREATED)
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
                "수정빌딩", "BUILDING", null, null, null, 999999, null, 6, null,
                null, null, null);

        mockMvc.perform(put("/api/facilities/{id}", facility.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    private FacilityCreateRequest createRequestWith(Integer builtYear, Integer inspectionCycleMonths) {
        return new FacilityCreateRequest(
                "검증빌딩", "BUILDING", null, null, null, builtYear, null, inspectionCycleMonths, null,
                null, null, null);
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

    // ── 시설물 등록 필드 확장(#628 / HAJA-347) ──
    // 대표 사진(photoUrls)은 Polalise DDL 검토 후 별도 후속으로 반영 예정(#632) — 이번 범위 테스트 제외.

    @Test
    void 시설물등록_초기등급메모포함_201_응답에반영() throws Exception {
        User owner = saveUser("owner11@haja.com");
        FacilityCreateRequest request = new FacilityCreateRequest(
                "테스트빌딩", "BUILDING", null, null, null, null, null, null, null,
                FacilityInitialGrade.C, null, "1층 로비 CCTV 점검 필요");

        mockMvc.perform(post("/api/facilities")
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.initialGrade").value("C"))
                .andExpect(jsonPath("$.data.memo").value("1층 로비 CCTV 점검 필요"));
    }

    @Test
    void 시설물등록_배정불가담당자_400_AUTH_INVALID_INSPECTOR() throws Exception {
        // assigneeUserId 가 요청자와 같은 회사 소속 INSPECTOR/ADMIN 이 아니면(여기서는 회사가 아예 없는
        // 일반 USER) AuthService.validateAssignableInspector 가 거부해야 한다.
        User owner = saveUser("owner13@haja.com");
        User notAssignable = saveUser("stranger2@haja.com");
        FacilityCreateRequest request = new FacilityCreateRequest(
                "테스트빌딩", "BUILDING", null, null, null, null, null, null, null,
                null, notAssignable.getId(), null);

        mockMvc.perform(post("/api/facilities")
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_INSPECTOR"));
    }

    // ── 배정 가능한 담당자 목록 조회(#690) ──

    @Test
    void 배정가능담당자목록_같은회사INSPECTOR_ADMIN만반환() throws Exception {
        User owner = saveUser("owner14@haja.com");
        Long companyId = owner.getCompanyId();
        User inspector = userRepository.saveAndFlush(User.builder()
                .email("inspector14@haja.com").name("점검자")
                .role(Role.INSPECTOR).passwordHash("$2a$10$hashed")
                .companyId(companyId).status(UserStatus.ACTIVE).build());
        companyMembershipRepository.saveAndFlush(CompanyMembership.approvedOwner(companyId, inspector.getId()));
        User plainUser = userRepository.saveAndFlush(User.builder()
                .email("plain14@haja.com").name("일반사용자")
                .role(Role.USER).passwordHash("$2a$10$hashed")
                .companyId(companyId).status(UserStatus.ACTIVE).build());
        companyMembershipRepository.saveAndFlush(CompanyMembership.approvedOwner(companyId, plainUser.getId()));

        mockMvc.perform(get("/api/facilities/assignable-users")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(inspector.getId()))
                .andExpect(jsonPath("$.data[0].email").doesNotExist());
    }

    @Test
    void 배정가능담당자목록_요청자멤버십없음_companyId포인터만으로는열람불가_400() throws Exception {
        // PR머신 P2 픽스 회귀 고정 — companyId 포인터만 있고 유효 APPROVED 멤버십이 없는 사용자는
        // 회사 명부(다른 구성원의 이름·역할)를 조회할 수 없어야 한다.
        User owner = saveUser("owner15@haja.com");
        Long companyId = owner.getCompanyId();
        User noMembership = userRepository.saveAndFlush(User.builder()
                .email("no-membership15@haja.com").name("멤버십없음")
                .role(Role.USER).passwordHash("$2a$10$hashed")
                .companyId(companyId).status(UserStatus.ACTIVE).build());
        // 의도적으로 companyMembershipRepository 저장 생략 — companyId 포인터만 존재.

        mockMvc.perform(get("/api/facilities/assignable-users")
                        .with(csrf()).with(authentication(authOf(noMembership))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_INSPECTOR"));
    }

    @Test
    void 배정가능담당자목록_미인증_401() throws Exception {
        mockMvc.perform(get("/api/facilities/assignable-users"))
                .andExpect(status().isUnauthorized());
    }

    // ── 시설물 현황 전용 목록(#540 ⑥, HAJA-378) ──

    @Test
    void 현황목록_회사시설없으면_200_빈배열() throws Exception {
        User owner = saveUser("status-owner1@haja.com");

        mockMvc.perform(get("/api/facilities/status")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void 현황목록_담당자와점검이력있는시설_모든필드반영() throws Exception {
        User owner = saveUser("status-owner2@haja.com");
        Long companyId = owner.getCompanyId();
        User inspector = saveInspector("status-inspector2@haja.com", companyId);
        LocalDate dueAt = LocalDate.now().plusDays(7);
        Facility facility = facilityRepository.save(Facility.builder()
                .companyId(companyId)
                .name("현황테스트빌딩")
                .type("BUILDING")
                .initialGrade(FacilityInitialGrade.B)
                .nextInspectionDueAt(dueAt)
                .assigneeUserId(inspector.getId())
                .build());
        LocalDate lastInspectedAt = LocalDate.now().minusDays(3);
        saveInspection(facility.getId(), owner.getId(), inspector.getId(), 1, LocalDate.now().minusDays(20));
        saveInspection(facility.getId(), owner.getId(), inspector.getId(), 2, lastInspectedAt);

        mockMvc.perform(get("/api/facilities/status")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].facilityId").value(facility.getId()))
                .andExpect(jsonPath("$.data[0].facilityName").value("현황테스트빌딩"))
                .andExpect(jsonPath("$.data[0].initialGrade").value("B"))
                .andExpect(jsonPath("$.data[0].nextInspectionDueAt").value(dueAt.toString()))
                .andExpect(jsonPath("$.data[0].dDay").value(7))
                .andExpect(jsonPath("$.data[0].assigneeUserId").value(inspector.getId()))
                .andExpect(jsonPath("$.data[0].assigneeName").value("점검자"))
                .andExpect(jsonPath("$.data[0].lastInspectedAt").value(lastInspectedAt.toString()));
    }

    @Test
    void 현황목록_담당자없고점검이력없는시설_null필드로반환_에러없음() throws Exception {
        User owner = saveUser("status-owner3@haja.com");
        Facility facility = saveFacility(owner.getId());

        mockMvc.perform(get("/api/facilities/status")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].facilityId").value(facility.getId()))
                .andExpect(jsonPath("$.data[0].initialGrade").value(nullValue()))
                .andExpect(jsonPath("$.data[0].nextInspectionDueAt").value(nullValue()))
                .andExpect(jsonPath("$.data[0].dDay").value(nullValue()))
                .andExpect(jsonPath("$.data[0].assigneeUserId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].assigneeName").value(nullValue()))
                .andExpect(jsonPath("$.data[0].lastInspectedAt").value(nullValue()));
    }

    @Test
    void 현황목록_타회사시설물은응답에없음() throws Exception {
        User owner = saveUser("status-owner4@haja.com");
        User otherOwner = saveUser("status-owner5@haja.com");
        saveFacility(otherOwner.getId());

        mockMvc.perform(get("/api/facilities/status")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void 현황목록_미인증_401() throws Exception {
        mockMvc.perform(get("/api/facilities/status"))
                .andExpect(status().isUnauthorized());
    }
}
