package com.hajacheck.core.defect.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
import java.util.Map;
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
 * GET /api/defects, GET /api/defects/{id} MVC 통합 테스트(HAJA-30).
 * FacilityControllerTest 와 동일하게 전역 시큐리티 필터체인이 ClientRegistrationRepository 를 요구해
 * @SpringBootTest+MockMvc(+PostgresTestSupport) 로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DefectControllerTest extends PostgresTestSupport {

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
    @Autowired
    private DefectRepository defectRepository;

    // HAJA-25 배정 검증 트리거(trg_inspections_check_assigned_inspector_company)가 assigned_inspector_id에
    // 승인+검증된 회사 소속 INSPECTOR/ADMIN 역할을 요구한다(DefectRepositoryTest.seedOwner 와 동일 픽스처).
    private User saveOwner(String email) {
        User owner = userRepository.save(User.builder()
                .email(email)
                .name("소유자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build());

        Company company = Company.createPendingReview(
                owner.getId(), "테스트회사-" + owner.getId(), "REG-" + owner.getId(), "대표자",
                "서울시 강남구", null, "https://files.example.com/registration.png", "{}");
        companyRepository.save(company);
        company.markBusinessVerified();
        company.approve(owner.getId());
        companyRepository.save(company);

        companyMembershipRepository.save(CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        owner.assignToCompany(company.getId());
        userRepository.save(owner);

        return owner;
    }

    private Facility saveFacility(Long ownerId) {
        return facilityRepository.save(Facility.builder()
                .ownerId(ownerId)
                .name("테스트빌딩")
                .type("BUILDING")
                .address("서울시 강남구")
                .build());
    }

    private Inspection saveInspection(Long facilityId, Long ownerId) {
        return inspectionRepository.save(Inspection.builder()
                .facilityId(facilityId)
                .createdBy(ownerId)
                .assignedInspectorId(ownerId)
                .roundNo(1)
                .inspectionDate(LocalDate.of(2026, 7, 1))
                .status(InspectionStatus.REVIEWED)
                .build());
    }

    private Defect saveDefect(Long inspectionId, DefectGrade grade, DefectStatus status) {
        return defectRepository.save(Defect.builder()
                .inspectionId(inspectionId)
                .type(DefectType.CRACK)
                .confidence(0.9)
                .grade(grade)
                .status(status)
                .reviewed(false)
                .deleted(false)
                .build());
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void 하자목록조회_본인소유_200_페이지응답() throws Exception {
        User owner = saveOwner("owner@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(get("/api/defects").with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].facilityName").value("테스트빌딩"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void 하자목록조회_타인소유하자는목록에안보임() throws Exception {
        User owner = saveOwner("owner2@haja.com");
        User stranger = saveOwner("stranger2@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(get("/api/defects").with(csrf()).with(authentication(authOf(stranger))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void 하자목록조회_등급필터적용() throws Exception {
        User owner = saveOwner("owner3@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);
        saveDefect(inspection.getId(), DefectGrade.E, DefectStatus.DETECTED);

        mockMvc.perform(get("/api/defects").param("grade", "E")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].grade").value("E"));
    }

    @Test
    void 하자목록조회_잘못된필터값_400_INVALID_INPUT() throws Exception {
        User owner = saveOwner("owner4@haja.com");

        mockMvc.perform(get("/api/defects").param("grade", "NOT_A_GRADE")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 하자목록조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/defects").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 하자상세조회_본인소유_200() throws Exception {
        User owner = saveOwner("owner5@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.D, DefectStatus.CONFIRMED);

        mockMvc.perform(get("/api/defects/{id}", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(defect.getId()))
                .andExpect(jsonPath("$.data.grade").value("D"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.facilityName").value("테스트빌딩"));
    }

    @Test
    void 하자상세조회_없는하자_404_DEFECT_NOT_FOUND() throws Exception {
        User owner = saveOwner("owner6@haja.com");

        mockMvc.perform(get("/api/defects/{id}", 999999L)
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEFECT_NOT_FOUND"));
    }

    // IDOR 회귀 테스트(필수) — 타 사용자 소유 하자 상세 조회는 404(리소스 존재 여부 비노출).
    @Test
    void 하자상세조회_타인소유하자_404_DEFECT_NOT_FOUND() throws Exception {
        User owner = saveOwner("owner7@haja.com");
        User stranger = saveOwner("stranger7@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(get("/api/defects/{id}", defect.getId())
                        .with(csrf()).with(authentication(authOf(stranger))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEFECT_NOT_FOUND"));
    }

    @Test
    void 하자상세조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/defects/{id}", 1L).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 하자상태전이_정상전이_200() throws Exception {
        User owner = saveOwner("owner8@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(patch("/api/defects/{id}/status", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "CONFIRMED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    void 하자상태전이_잘못된enum값_400_INVALID_INPUT() throws Exception {
        User owner = saveOwner("owner9@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(patch("/api/defects/{id}/status", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "NOT_A_STATUS"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 하자상태전이_역행요청_409_INVALID_STATE_TRANSITION() throws Exception {
        User owner = saveOwner("owner10@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        // DETECTED → ACTION_PENDING 은 CONFIRMED 단계를 건너뛰는 스킵 전이라 거부되어야 한다.
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(patch("/api/defects/{id}/status", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "ACTION_PENDING"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
    }

    // IDOR 회귀 테스트(필수) — 타 사용자 소유 하자 상태 전이는 404(리소스 존재 여부 비노출).
    @Test
    void 하자상태전이_타인소유하자_404_DEFECT_NOT_FOUND() throws Exception {
        User owner = saveOwner("owner11@haja.com");
        User stranger = saveOwner("stranger11@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(patch("/api/defects/{id}/status", defect.getId())
                        .with(csrf()).with(authentication(authOf(stranger)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "CONFIRMED"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEFECT_NOT_FOUND"));
    }

    @Test
    void 하자상태전이_미인증_401() throws Exception {
        mockMvc.perform(patch("/api/defects/{id}/status", 1L).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "CONFIRMED"))))
                .andExpect(status().isUnauthorized());
    }
}
