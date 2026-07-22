package com.hajacheck.core.defect.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DefectControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
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

    private Inspection saveInspection(Long facilityId) {
        return inspectionRepository.save(Inspection.builder()
                .facilityId(facilityId)
                .createdBy(1L)
                .assignedInspectorId(1L)
                .roundNo(1)
                .inspectionDate(LocalDate.of(2026, 7, 20))
                .status(InspectionStatus.CREATED)
                .build());
    }

    private Defect saveDefect(Long inspectionId) {
        return defectRepository.save(Defect.builder()
                .inspectionId(inspectionId)
                .type(DefectType.CRACK)
                .bboxX(10.0)
                .bboxY(20.0)
                .bboxW(50.0)
                .bboxH(60.0)
                .confidence(0.95)
                .grade(DefectGrade.A)
                .status(DefectStatus.DETECTED)
                .reviewed(false)
                .deleted(false)
                .crackWidthMm(2.5)
                .crackLengthMm(100.0)
                .build());
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void 하자단건조회_본인시설_200_정상응답() throws Exception {
        User owner = saveUser("owner@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId());
        Defect defect = saveDefect(inspection.getId());

        mockMvc.perform(get("/api/defects/{id}", defect.getId())
                        .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(defect.getId()))
                .andExpect(jsonPath("$.data.inspectionId").value(inspection.getId()))
                .andExpect(jsonPath("$.data.type").value("CRACK"))
                .andExpect(jsonPath("$.data.confidence").value(0.95))
                .andExpect(jsonPath("$.data.grade").value("A"))
                .andExpect(jsonPath("$.data.status").value("DETECTED"))
                .andExpect(jsonPath("$.data.facilityType").value("BUILDING"));
    }

    @Test
    void 하자단건조회_없는하자_404_DEFECT_NOT_FOUND() throws Exception {
        User owner = saveUser("owner2@haja.com");

        mockMvc.perform(get("/api/defects/{id}", 999999L)
                        .with(authentication(authOf(owner))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEFECT_NOT_FOUND"));
    }

    @Test
    void 하자단건조회_타인시설_404_FACILITY_NOT_FOUND() throws Exception {
        User owner = saveUser("owner3@haja.com");
        User stranger = saveUser("stranger@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId());
        Defect defect = saveDefect(inspection.getId());

        mockMvc.perform(get("/api/defects/{id}", defect.getId())
                        .with(authentication(authOf(stranger))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FACILITY_NOT_FOUND"));
    }
}
