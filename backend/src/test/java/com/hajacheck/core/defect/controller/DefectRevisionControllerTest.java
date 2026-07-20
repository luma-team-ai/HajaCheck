package com.hajacheck.core.defect.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.defect.dto.DefectRevisionRequest;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.support.PostgresTestSupport;
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
 * 검수 API 통합 테스트(GET /api/inspections/{id}/defects, PATCH /api/defects/{id}).
 * 최신 테스트 패턴(NotificationControllerTest 참고): @SpringBootTest + MockMvc + PostgresTestSupport.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DefectRevisionControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FacilityRepository facilityRepository;
    @Autowired
    private InspectionRepository inspectionRepository;
    @Autowired
    private DefectRepository defectRepository;

    private User saveUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .name("테스트사용자")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
    }

    private Facility saveFacility(User owner) {
        return facilityRepository.save(Facility.builder()
                .ownerId(owner.getId())
                .name("테스트시설물")
                .type("건축물")
                .build());
    }

    private Inspection saveInspection(Facility facility, User assignedInspector) {
        return inspectionRepository.save(Inspection.builder()
                .facilityId(facility.getId())
                .assignedInspectorId(assignedInspector.getId())
                .build());
    }

    private Defect saveDefect(Inspection inspection, DefectGrade grade, DefectStatus status) {
        return defectRepository.save(Defect.builder()
                .inspectionId(inspection.getId())
                .type(DefectType.CRACK)
                .confidence(0.95)
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

    // ============ GET /api/inspections/{id}/defects 테스트 ============

    @Test
    void GET_정상조회_200() throws Exception {
        User owner = saveUser("facility-owner@haja.com");
        User inspector = saveUser("inspector@haja.com");
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, inspector);
        Defect defect1 = saveDefect(inspection, DefectGrade.C, DefectStatus.DETECTED);
        Defect defect2 = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED);

        mockMvc.perform(get("/api/inspections/{id}/defects", inspection.getId())
                .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(defect1.getId()))
                .andExpect(jsonPath("$.data[0].grade").value("C"))
                .andExpect(jsonPath("$.data[0].status").value("DETECTED"))
                .andExpect(jsonPath("$.data[1].id").value(defect2.getId()))
                .andExpect(jsonPath("$.data[1].grade").value("B"));
    }

    @Test
    void GET_삭제된하자제외_200() throws Exception {
        User owner = saveUser("owner2@haja.com");
        User inspector = saveUser("inspector2@haja.com");
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, inspector);
        Defect notDeleted = saveDefect(inspection, DefectGrade.C, DefectStatus.DETECTED);
        Defect deleted = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED);
        deleted.softDelete();
        defectRepository.save(deleted);

        mockMvc.perform(get("/api/inspections/{id}/defects", inspection.getId())
                .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(notDeleted.getId()));
    }

    @Test
    void GET_타인점검_404() throws Exception {
        User owner = saveUser("owner3@haja.com");
        User stranger = saveUser("stranger@haja.com");
        User inspector = saveUser("inspector3@haja.com");
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, inspector);
        saveDefect(inspection, DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(get("/api/inspections/{id}/defects", inspection.getId())
                .with(authentication(authOf(stranger))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INSPECTION_NOT_FOUND"));
    }

    @Test
    void GET_미존재점검_404() throws Exception {
        User owner = saveUser("owner4@haja.com");

        mockMvc.perform(get("/api/inspections/{id}/defects", 999999L)
                .with(authentication(authOf(owner))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INSPECTION_NOT_FOUND"));
    }

    @Test
    void GET_미인증_401() throws Exception {
        mockMvc.perform(get("/api/inspections/{id}/defects", 1L))
                .andExpect(status().isUnauthorized());
    }

    // ============ PATCH /api/defects/{id} 테스트 ============

    @Test
    void PATCH_등급변경_정상() throws Exception {
        User owner = saveUser("owner5@haja.com");
        User inspector = saveUser("inspector5@haja.com");
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.C, DefectStatus.DETECTED);

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .grade(DefectGrade.A)
                .reason("재검수 결과 A등급으로 상향")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.grade").value("A"))
                .andExpect(jsonPath("$.data.isReviewed").value(true));
    }

    @Test
    void PATCH_오탐삭제_정상() throws Exception {
        User owner = saveUser("owner6@haja.com");
        User inspector = saveUser("inspector6@haja.com");
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED);

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .deleted(true)
                .reason("오탐이므로 삭제")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void PATCH_gradeAndDeleted둘다_400() throws Exception {
        User owner = saveUser("owner7@haja.com");
        User inspector = saveUser("inspector7@haja.com");
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED);

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .grade(DefectGrade.A)
                .deleted(true)
                .reason("테스트")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void PATCH_둘다아님_400() throws Exception {
        User owner = saveUser("owner8@haja.com");
        User inspector = saveUser("inspector8@haja.com");
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED);

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .reason("테스트")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void PATCH_deletedFalse_400() throws Exception {
        User owner = saveUser("owner9@haja.com");
        User inspector = saveUser("inspector9@haja.com");
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED);

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .deleted(false)
                .reason("테스트")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void PATCH_reasonBlank_400() throws Exception {
        User owner = saveUser("owner10@haja.com");
        User inspector = saveUser("inspector10@haja.com");
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED);

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .grade(DefectGrade.A)
                .reason("")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void PATCH_RESOLVED상태_409() throws Exception {
        User owner = saveUser("owner11@haja.com");
        User inspector = saveUser("inspector11@haja.com");
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.C, DefectStatus.RESOLVED);

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .grade(DefectGrade.A)
                .reason("테스트")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void PATCH_미존재하자_404() throws Exception {
        User owner = saveUser("owner12@haja.com");

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .grade(DefectGrade.A)
                .reason("테스트")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", 999999L)
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEFECT_NOT_FOUND"));
    }

    @Test
    void PATCH_타인점검_404() throws Exception {
        User owner = saveUser("owner13@haja.com");
        User stranger = saveUser("stranger2@haja.com");
        User inspector = saveUser("inspector13@haja.com");
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED);

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .grade(DefectGrade.A)
                .reason("테스트")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(authentication(authOf(stranger)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEFECT_NOT_FOUND"));
    }

    @Test
    void PATCH_미인증_401() throws Exception {
        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .grade(DefectGrade.A)
                .reason("테스트")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", 1L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}
