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
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.support.PostgresTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    @PersistenceContext
    private EntityManager entityManager;
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
    @Autowired
    private MediaRepository mediaRepository;

    // HAJA-25 배정 검증 트리거(trg_inspections_check_assigned_inspector_company)가 assigned_inspector_id에
    // 승인+검증된 회사 소속 INSPECTOR/ADMIN 역할을 요구한다(DefectRepositoryTest.seedOwner 와 동일 픽스처).
    private User saveOwner(String email) {
        // saveAndFlush로 각 단계를 즉시 반영한다 — 그렇지 않으면 Hibernate가 한 플러시에서 INSERT를
        // UPDATE보다 먼저 실행해(inspections INSERT가 company/user UPDATE보다 앞서 큐잉됨) HAJA-25
        // 배정 검증 트리거가 아직 반영되지 않은(PENDING_REVIEW/company_id=null) 상태로 검증해 실패한다.
        User owner = userRepository.saveAndFlush(User.builder()
                .email(email)
                .name("소유자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build());

        Company company = Company.createPendingReview(
                owner.getId(), "테스트회사-" + owner.getId(), "REG-" + owner.getId(), "대표자",
                "서울시 강남구", null, "https://files.example.com/registration.png", "{}");
        companyRepository.saveAndFlush(company);
        company.markBusinessVerified();
        company.approve(owner.getId());
        companyRepository.saveAndFlush(company);

        companyMembershipRepository.saveAndFlush(CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        owner.assignToCompany(company.getId());
        userRepository.saveAndFlush(owner);

        return owner;
    }

    private Facility saveFacility(Long ownerId) {
        return facilityRepository.save(Facility.builder()
                .companyId(userRepository.findById(ownerId).orElseThrow().getCompanyId())
                .name("테스트빌딩")
                .type("BUILDING")
                .address("서울시 강남구")
                .build());
    }

    private Inspection saveInspection(Long facilityId, Long ownerId) {
        return saveInspection(facilityId, ownerId, 1);
    }

    // HAJA-437 회차 간 대응 하자 확정 테스트용 — 같은 시설물의 다른 회차 점검을 만들 때 사용.
    private Inspection saveInspection(Long facilityId, Long ownerId, int roundNo) {
        return inspectionRepository.save(Inspection.builder()
                .facilityId(facilityId)
                .createdBy(ownerId)
                .assignedInspectorId(ownerId)
                .roundNo(roundNo)
                .inspectionDate(LocalDate.of(2026, 7, 1).plusMonths(roundNo - 1))
                .status(InspectionStatus.REVIEWED)
                .build());
    }

    private Defect saveDefect(Long inspectionId, DefectGrade grade, DefectStatus status) {
        Defect saved = defectRepository.save(Defect.builder()
                .inspectionId(inspectionId)
                .type(DefectType.CRACK)
                .confidence(0.9)
                .grade(grade)
                .status(status)
                .reviewed(false)
                .deleted(false)
                .build());
        // 저장 직후 같은 영속성 컨텍스트에서 MockMvc가 곧바로 조회하면, join fetch로 가져온 연관관계를
        // Hibernate가 이미 관리 중인 엔티티에 재적용하지 않아 inspection이 null로 남는다 — flush+clear로
        // 컨텍스트를 비워 이후 컨트롤러 호출이 DB에서 fresh하게 join fetch되도록 한다.
        entityManager.flush();
        entityManager.clear();
        return saved;
    }

    // #1128 targetStatus 테스트용 — DefectRevisionControllerTest.saveMedia 와 동일 픽스처 패턴.
    private Media saveMedia(Long inspectionId) {
        return mediaRepository.save(Media.builder()
                .inspectionId(inspectionId)
                .fileType(MediaFileType.IMAGE)
                .originalUrl("s3://test-bucket/original.jpg")
                .thumbnailUrl("s3://test-bucket/thumb.jpg")
                .mimeSignatureVerified(true)
                .mimeType("image/jpeg")
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
    void 하자목록조회_등급필터는이상_임계값의미_더심각한등급도포함() throws Exception {
        // PR #372 code-reviewer P2 회귀 방지 — UI 칩 "등급: D 이상"과 달리 백엔드가 grade == D
        // 정확 일치만 반환해 더 심각한 E 등급이 누락되던 결함. grade=D 필터에 D·E 둘 다 잡히고
        // 더 양호한 C는 제외돼야 한다.
        User owner = saveOwner("owner3b@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);
        saveDefect(inspection.getId(), DefectGrade.D, DefectStatus.DETECTED);
        saveDefect(inspection.getId(), DefectGrade.E, DefectStatus.DETECTED);

        mockMvc.perform(get("/api/defects").param("grade", "D")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[*].grade")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("D", "E")));
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
    void 하자상태전이_사유없는건너뛰기요청_400_INVALID_INPUT() throws Exception {
        User owner = saveOwner("owner10@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        // DETECTED → IN_PROGRESS 는 CONFIRMED 단계를 건너뛰는 스킵 전이라 사유 없이는 거부되어야 한다.
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(patch("/api/defects/{id}/status", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "IN_PROGRESS"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 하자상태전이_사유있는건너뛰기요청_200() throws Exception {
        User owner = saveOwner("owner12@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(patch("/api/defects/{id}/status", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("status", "IN_PROGRESS", "reason", "경미한 하자라 검수확정 생략"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
    }

    @Test
    void 하자상태전이_해결상태이탈요청_409_INVALID_STATE_TRANSITION() throws Exception {
        User owner = saveOwner("owner13@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.RESOLVED);

        mockMvc.perform(patch("/api/defects/{id}/status", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("status", "IN_PROGRESS", "reason", "재검토 필요"))))
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

    @Test
    void 하자활동기록조회_본인소유_200_상태전이이력포함() throws Exception {
        User owner = saveOwner("owner14@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(patch("/api/defects/{id}/status", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "CONFIRMED"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/defects/{id}/revisions", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].fieldChanged").value("status"))
                .andExpect(jsonPath("$.data.content[0].oldValue").value("DETECTED"))
                .andExpect(jsonPath("$.data.content[0].newValue").value("CONFIRMED"));
    }

    // findByDefectIdOrderByCreatedAtDesc가 실제 DB에서 최신순으로 정렬해 반환하는지 검증(self-review
    // 발견 — 기존 테스트는 이력 1건뿐이라 정렬 자체를 검증하지 못했음).
    @Test
    void 하자활동기록조회_두건이상이면_최신순으로반환() throws Exception {
        User owner = saveOwner("owner18@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(patch("/api/defects/{id}/status", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "CONFIRMED"))))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/defects/{id}/status", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "IN_PROGRESS"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/defects/{id}/revisions", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].oldValue").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.content[0].newValue").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.content[1].oldValue").value("DETECTED"))
                .andExpect(jsonPath("$.data.content[1].newValue").value("CONFIRMED"));
    }

    @Test
    void 하자활동기록조회_이력없으면빈페이지() throws Exception {
        User owner = saveOwner("owner15@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(get("/api/defects/{id}/revisions", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void 하자활동기록조회_없는하자_404_DEFECT_NOT_FOUND() throws Exception {
        User owner = saveOwner("owner16@haja.com");

        mockMvc.perform(get("/api/defects/{id}/revisions", 999999L)
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEFECT_NOT_FOUND"));
    }

    // IDOR 회귀 테스트(필수) — 타 사용자 소유 하자의 활동기록 조회는 404(리소스 존재 여부 비노출).
    @Test
    void 하자활동기록조회_타인소유하자_404_DEFECT_NOT_FOUND() throws Exception {
        User owner = saveOwner("owner17@haja.com");
        User stranger = saveOwner("stranger17@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(get("/api/defects/{id}/revisions", defect.getId())
                        .with(csrf()).with(authentication(authOf(stranger))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEFECT_NOT_FOUND"));
    }

    @Test
    void 하자활동기록조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/defects/{id}/revisions", 1L).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    // ── #970 갭3: 하자 위치 사후 편집 ──

    @Test
    void 하자위치편집_본인소유_200_위치반영() throws Exception {
        User owner = saveOwner("owner19@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(patch("/api/defects/{id}/location", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("location", "외벽 동측 12층 부근"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.location").value("외벽 동측 12층 부근"));
    }

    @Test
    void 하자위치편집_빈문자열은null로정규화() throws Exception {
        User owner = saveOwner("owner20@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(patch("/api/defects/{id}/location", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("location", ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.location").doesNotExist());
    }

    // IDOR 회귀 테스트(필수) — 타 사용자 소유 하자 위치 편집은 404(리소스 존재 여부 비노출).
    @Test
    void 하자위치편집_타인소유하자_404_DEFECT_NOT_FOUND() throws Exception {
        User owner = saveOwner("owner21@haja.com");
        User stranger = saveOwner("stranger21@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(patch("/api/defects/{id}/location", defect.getId())
                        .with(csrf()).with(authentication(authOf(stranger)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("location", "외벽 동측 12층 부근"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEFECT_NOT_FOUND"));
    }

    @Test
    void 하자위치편집_미인증_401() throws Exception {
        mockMvc.perform(patch("/api/defects/{id}/location", 1L).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("location", "아무 위치"))))
                .andExpect(status().isUnauthorized());
    }

    // ── HAJA-437: 회차 간 대응 하자 확정 ──

    @Test
    void 이전회차하자확정_같은시설물더이전회차_200() throws Exception {
        User owner = saveOwner("owner22@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection round1 = saveInspection(facility.getId(), owner.getId(), 1);
        Inspection round2 = saveInspection(facility.getId(), owner.getId(), 2);
        Defect previous = saveDefect(round1.getId(), DefectGrade.C, DefectStatus.DETECTED);
        Defect current = saveDefect(round2.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(patch("/api/defects/{id}/previous-defect", current.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("previousDefectId", previous.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.previousDefectId").value(previous.getId()));
    }

    @Test
    void 이전회차하자확정_다른시설물이면400_DEFECT_PREVIOUS_DEFECT_INVALID() throws Exception {
        User owner = saveOwner("owner23@haja.com");
        Facility facility = saveFacility(owner.getId());
        Facility otherFacility = saveFacility(owner.getId());
        Inspection round1 = saveInspection(otherFacility.getId(), owner.getId(), 1);
        Inspection round2 = saveInspection(facility.getId(), owner.getId(), 2);
        Defect otherFacilityDefect = saveDefect(round1.getId(), DefectGrade.C, DefectStatus.DETECTED);
        Defect current = saveDefect(round2.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(patch("/api/defects/{id}/previous-defect", current.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("previousDefectId", otherFacilityDefect.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("DEFECT_PREVIOUS_DEFECT_INVALID"));
    }

    @Test
    void 이전회차하자확정_같은회차이후면400_DEFECT_PREVIOUS_DEFECT_INVALID() throws Exception {
        User owner = saveOwner("owner24@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection round1 = saveInspection(facility.getId(), owner.getId(), 1);
        Inspection round2 = saveInspection(facility.getId(), owner.getId(), 2);
        Defect earlier = saveDefect(round1.getId(), DefectGrade.C, DefectStatus.DETECTED);
        Defect later = saveDefect(round2.getId(), DefectGrade.C, DefectStatus.DETECTED);

        // earlier(1회차)를 later(2회차)보다 "더 나중" 것으로 지정 시도 — 방향이 반대라 거부되어야 한다.
        mockMvc.perform(patch("/api/defects/{id}/previous-defect", earlier.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("previousDefectId", later.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("DEFECT_PREVIOUS_DEFECT_INVALID"));
    }

    // IDOR 회귀 테스트(필수) — 대상 하자가 타사 소유(또는 미존재)면 404가 아니라 400으로 통일 응답
    // (previousDefectId 자체가 리소스 식별자가 아니라 body 필드라 DEFECT_NOT_FOUND와 분리).
    @Test
    void 이전회차하자확정_대상하자가타사소유_400_DEFECT_PREVIOUS_DEFECT_INVALID() throws Exception {
        User owner = saveOwner("owner25@haja.com");
        User stranger = saveOwner("stranger25@haja.com");
        Facility facility = saveFacility(owner.getId());
        Facility strangerFacility = saveFacility(stranger.getId());
        Inspection round1 = saveInspection(strangerFacility.getId(), stranger.getId(), 1);
        Inspection round2 = saveInspection(facility.getId(), owner.getId(), 2);
        Defect strangerDefect = saveDefect(round1.getId(), DefectGrade.C, DefectStatus.DETECTED);
        Defect current = saveDefect(round2.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(patch("/api/defects/{id}/previous-defect", current.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("previousDefectId", strangerDefect.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("DEFECT_PREVIOUS_DEFECT_INVALID"));
    }

    // IDOR 회귀 테스트(필수) — 편집 대상(현재) 하자 자체가 타인 소유면 404(리소스 존재 여부 비노출).
    @Test
    void 이전회차하자확정_현재하자가타인소유_404_DEFECT_NOT_FOUND() throws Exception {
        User owner = saveOwner("owner26@haja.com");
        User stranger = saveOwner("stranger26@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection round1 = saveInspection(facility.getId(), owner.getId(), 1);
        Inspection round2 = saveInspection(facility.getId(), owner.getId(), 2);
        Defect previous = saveDefect(round1.getId(), DefectGrade.C, DefectStatus.DETECTED);
        Defect current = saveDefect(round2.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(patch("/api/defects/{id}/previous-defect", current.getId())
                        .with(csrf()).with(authentication(authOf(stranger)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("previousDefectId", previous.getId()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEFECT_NOT_FOUND"));
    }

    @Test
    void 이전회차하자확정_미인증_401() throws Exception {
        mockMvc.perform(patch("/api/defects/{id}/previous-defect", 1L).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("previousDefectId", 2L))))
                .andExpect(status().isUnauthorized());
    }

    // ── #1128: 조치 등록 폼 targetStatus(진행상태 select) ──

    @Test
    void 조치등록_CONFIRMED에서_targetStatus_IN_PROGRESS_200() throws Exception {
        User owner = saveOwner("owner27@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.CONFIRMED);
        Media media = saveMedia(inspection.getId());

        mockMvc.perform(patch("/api/defects/{id}/action", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "actionMediaId", media.getId(),
                                "actionContent", "조치 착수 — 균열 부위 실측 완료",
                                "actionDate", "2026-07-28",
                                "actionAssigneeId", owner.getId(),
                                "targetStatus", "IN_PROGRESS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
    }

    @Test
    void 조치등록_IN_PROGRESS에서_targetStatus_RESOLVED_200() throws Exception {
        User owner = saveOwner("owner28@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.IN_PROGRESS);
        Media media = saveMedia(inspection.getId());

        mockMvc.perform(patch("/api/defects/{id}/action", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "actionMediaId", media.getId(),
                                "actionContent", "균열 부위 보수 완료",
                                "actionDate", "2026-07-28",
                                "actionAssigneeId", owner.getId(),
                                "targetStatus", "RESOLVED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));
    }

    // CONFIRMED → RESOLVED 는 IN_PROGRESS 단계를 건너뛰는 스킵 전이라, targetStatus 를 통해서도
    // (사유 입력란이 없는 이 폼에서는) 여전히 거부돼야 한다 — "조치 없이 완료 처리" 방지 회귀선.
    @Test
    void 조치등록_CONFIRMED에서_targetStatus_RESOLVED_건너뛴전이_400_INVALID_INPUT() throws Exception {
        User owner = saveOwner("owner29@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.CONFIRMED);
        Media media = saveMedia(inspection.getId());

        mockMvc.perform(patch("/api/defects/{id}/action", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "actionMediaId", media.getId(),
                                "actionContent", "조치 완료 처리 시도",
                                "actionDate", "2026-07-28",
                                "actionAssigneeId", owner.getId(),
                                "targetStatus", "RESOLVED"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    // UT-073(FR/AP 조치 결과 등록) — DefectActionResultRequest의 필수 필드 검증이 실제로
    // 400 INVALID_INPUT으로 표면화되는지. @NotNull/@NotBlank는 붙어 있었지만 이를 고정하는
    // 테스트가 없어, 애노테이션이 지워지거나 @Valid가 빠져도 아무도 못 잡는 상태였다.
    @ParameterizedTest(name = "{0} 누락 시 400")
    @ValueSource(strings = {"actionMediaId", "actionContent", "actionDate", "actionAssigneeId"})
    void 조치등록_필수필드누락_400_INVALID_INPUT(String missingField) throws Exception {
        User owner = saveOwner("owner-req-" + missingField.toLowerCase() + "@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.CONFIRMED);
        Media media = saveMedia(inspection.getId());

        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "actionMediaId", media.getId(),
                "actionContent", "조치 착수 — 균열 부위 실측 완료",
                "actionDate", "2026-07-28",
                "actionAssigneeId", owner.getId(),
                "targetStatus", "IN_PROGRESS"));
        body.remove(missingField);

        mockMvc.perform(patch("/api/defects/{id}/action", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 조치등록_actionContent공백만_400_INVALID_INPUT() throws Exception {
        // @NotBlank — null이 아니라 공백 문자열로 우회하는 경로도 함께 막는다.
        User owner = saveOwner("owner-req-blank@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.CONFIRMED);
        Media media = saveMedia(inspection.getId());

        mockMvc.perform(patch("/api/defects/{id}/action", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "actionMediaId", media.getId(),
                                "actionContent", "   ",
                                "actionDate", "2026-07-28",
                                "actionAssigneeId", owner.getId(),
                                "targetStatus", "IN_PROGRESS"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    // ── #1193/HAJA-569: 조치 등록 이력 조회 — IN_PROGRESS 다중 등록 지원 ──

    @Test
    void 조치이력조회_IN_PROGRESS유지재제출_두건이력_최신순반환() throws Exception {
        User owner = saveOwner("owner30@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.IN_PROGRESS);
        Media media1 = saveMedia(inspection.getId());
        Media media2 = saveMedia(inspection.getId());

        mockMvc.perform(patch("/api/defects/{id}/action", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "actionMediaId", media1.getId(),
                                "actionContent", "1차 보수",
                                "actionDate", "2026-07-28",
                                "actionAssigneeId", owner.getId(),
                                "targetStatus", "IN_PROGRESS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        mockMvc.perform(patch("/api/defects/{id}/action", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "actionMediaId", media2.getId(),
                                "actionContent", "2차 보수",
                                "actionDate", "2026-07-29",
                                "actionAssigneeId", owner.getId(),
                                "targetStatus", "IN_PROGRESS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));

        mockMvc.perform(get("/api/defects/{id}/action-logs", defect.getId())
                        .param("phase", "IN_PROGRESS")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].actionContent").value("2차 보수"))
                .andExpect(jsonPath("$.data[0].photoUrl").value("/api/media/" + media2.getId() + "/thumbnail"))
                .andExpect(jsonPath("$.data[1].actionContent").value("1차 보수"));

        // 상태가 실제로 바뀌지 않은 제출이라 "status" 필드 변경 이력은 남지 않는다(defect_revisions
        // 미기록) — 단, 2차 제출이 1차 제출의 actionContent/actionMediaId를 덮어쓴 감사기록(#1128
        // P2-1, 상태 변경과 무관하게 항상 남는 기존 동작)은 별개로 남는다.
        mockMvc.perform(get("/api/defects/{id}/revisions", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(jsonPath("$.data.content[*].fieldChanged")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("status"))));
    }

    @Test
    void 조치이력조회_RESOLVED로전이하면_해당제출도IN_PROGRESS이력에그대로남음() throws Exception {
        // RESOLVED 전이 후에도 과거 조치중 이력은 계속 조회 가능해야 한다(플랜 요구사항 §3).
        User owner = saveOwner("owner31@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.IN_PROGRESS);
        Media media = saveMedia(inspection.getId());

        mockMvc.perform(patch("/api/defects/{id}/action", defect.getId())
                        .with(csrf()).with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "actionMediaId", media.getId(),
                                "actionContent", "보수 완료",
                                "actionDate", "2026-07-28",
                                "actionAssigneeId", owner.getId(),
                                "targetStatus", "RESOLVED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));

        mockMvc.perform(get("/api/defects/{id}/action-logs", defect.getId())
                        .param("phase", "RESOLVED")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].actionContent").value("보수 완료"));
    }

    @Test
    void 조치이력조회_허용안된phase_400_INVALID_INPUT() throws Exception {
        User owner = saveOwner("owner32@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(get("/api/defects/{id}/action-logs", defect.getId())
                        .param("phase", "CONFIRMED")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 조치이력조회_이력없으면빈배열() throws Exception {
        User owner = saveOwner("owner33@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(get("/api/defects/{id}/action-logs", defect.getId())
                        .param("phase", "IN_PROGRESS")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // IDOR 회귀 테스트(필수) — 타 사용자 소유 하자의 조치 이력 조회는 404(리소스 존재 여부 비노출).
    @Test
    void 조치이력조회_타인소유하자_404_DEFECT_NOT_FOUND() throws Exception {
        User owner = saveOwner("owner34@haja.com");
        User stranger = saveOwner("stranger34@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId());
        Defect defect = saveDefect(inspection.getId(), DefectGrade.C, DefectStatus.DETECTED);

        mockMvc.perform(get("/api/defects/{id}/action-logs", defect.getId())
                        .param("phase", "IN_PROGRESS")
                        .with(csrf()).with(authentication(authOf(stranger))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEFECT_NOT_FOUND"));
    }

    @Test
    void 조치이력조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/defects/{id}/action-logs", 1L)
                        .param("phase", "IN_PROGRESS")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
