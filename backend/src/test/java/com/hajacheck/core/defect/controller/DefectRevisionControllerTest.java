package com.hajacheck.core.defect.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.CompanyMembershipStatus;
import com.hajacheck.auth.entity.CompanyStatus;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.defect.dto.DefectCreateRequest;
import com.hajacheck.core.defect.dto.DefectRevisionRequest;
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
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 검수 API 통합 테스트(GET /api/inspections/{id}/defects, POST /api/inspections/{id}/defects, PATCH /api/defects/{id}).
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
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final java.util.concurrent.atomic.AtomicLong BRN_SEQ = new java.util.concurrent.atomic.AtomicLong(10_000_000_000L);

    private Company saveCompany(String name) {
        long brn = BRN_SEQ.getAndIncrement();
        String ownerEmail = "owner" + brn + "@haja.com";
        // 1. owner 사용자를 companyId 없이 생성 (ID 생성용)
        User tempOwner = userRepository.save(User.builder()
                .email(ownerEmail)
                .name("회사소유자")
                .role(Role.ADMIN)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());

        // 2. Company 생성 (owner ID = tempOwner.id)
        Company company = companyRepository.save(Company.createPendingReview(
                tempOwner.getId(), name, String.valueOf(brn), "대표",
                "서울시", null, "http://files/brn.png", "{}"));

        // 3-4. Company/User 상태 업데이트 (enum 컬럼은 명시적 캐스트 필요, timestamptz는 java.sql.Timestamp로 바인딩)
        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
        userRepository.flush();
        companyRepository.flush();

        // owner.company_id 업데이트
        jdbcTemplate.update("UPDATE users SET company_id = ? WHERE id = ?",
                company.getId(), tempOwner.getId());

        // Company 상태 업데이트
        jdbcTemplate.update(
                "UPDATE companies SET status = ?::company_status_type, " +
                "verification_status = ?::business_verification_status_type, " +
                "verified_at = ?, reviewed_by = ?, reviewed_at = ? WHERE id = ?",
                "APPROVED", "VERIFIED", now, tempOwner.getId(), now, company.getId());

        // 5. owner의 멤버십 생성
        companyMembershipRepository.save(CompanyMembership.approvedOwner(company.getId(), tempOwner.getId()));

        return company;
    }

    private User saveUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .name("테스트사용자")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
    }

    private User saveInspector(String email, Company company) {
        User inspector = userRepository.save(User.builder()
                .email(email)
                .name("테스트점검자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .companyId(company.getId())
                .build());

        companyMembershipRepository.save(CompanyMembership.approvedOwner(company.getId(), inspector.getId()));

        return inspector;
    }

    private void addCompanyMembership(User user, Company company) {
        // 엔티티 메서드로 companyId를 갱신해 인메모리 user 객체와 DB를 함께 동기화한다.
        // (raw JDBC UPDATE만 하면 이후 saveFacility(owner) 등이 stale companyId=null을 참조하게 된다)
        user.assignToCompany(company.getId());
        userRepository.saveAndFlush(user);

        // 멤버십 생성
        companyMembershipRepository.save(CompanyMembership.approvedOwner(company.getId(), user.getId()));
    }

    private Facility saveFacility(User owner) {
        return facilityRepository.save(Facility.builder()
                .companyId(owner.getCompanyId())
                .name("테스트시설물")
                .type("건축물")
                .build());
    }

    private Inspection saveInspection(Facility facility, User createdBy, User assignedInspector) {
        return inspectionRepository.save(Inspection.builder()
                .facilityId(facility.getId())
                .createdBy(createdBy.getId())
                .assignedInspectorId(assignedInspector.getId())
                .roundNo(1)
                .inspectionDate(java.time.LocalDate.now())
                .build());
    }

    private Media saveMedia(Inspection inspection) {
        return mediaRepository.save(Media.builder()
                .inspectionId(inspection.getId())
                .fileType(MediaFileType.IMAGE)
                .originalUrl("s3://test-bucket/original.jpg")
                .thumbnailUrl("s3://test-bucket/thumb.jpg")
                .mimeSignatureVerified(true)
                .mimeType("image/jpeg")
                .build());
    }

    private Defect saveDefect(Inspection inspection, DefectGrade grade, DefectStatus status) {
        return saveDefect(inspection, grade, status, null);
    }

    private Defect saveDefect(Inspection inspection, DefectGrade grade, DefectStatus status, Long mediaId) {
        return defectRepository.save(Defect.builder()
                .inspectionId(inspection.getId())
                .mediaId(mediaId)
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
        Company company = saveCompany("회사1");
        User owner = saveUser("facility-owner@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);
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
                .andExpect(jsonPath("$.data[0].mediaId").isEmpty())
                .andExpect(jsonPath("$.data[0].imageUrl").isEmpty())
                .andExpect(jsonPath("$.data[1].id").value(defect2.getId()))
                .andExpect(jsonPath("$.data[1].grade").value("B"));
    }

    @Test
    void GET_mediaIdPresent_imageUrlReturned_200() throws Exception {
        // mediaId가 있는 하자는 imageUrl이 생성돼야 한다
        Company company = saveCompany("회사26");
        User owner = saveUser("owner26@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector26@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);
        Media media = saveMedia(inspection);
        Defect defectWithMedia = saveDefect(inspection, DefectGrade.A, DefectStatus.DETECTED, media.getId());

        mockMvc.perform(get("/api/inspections/{id}/defects", inspection.getId())
                .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(defectWithMedia.getId()))
                .andExpect(jsonPath("$.data[0].mediaId").value(media.getId()))
                .andExpect(jsonPath("$.data[0].imageUrl").value("/api/media/" + media.getId() + "/thumbnail"));
    }

    @Test
    void GET_mediaIdNull_imageUrlNull_200() throws Exception {
        // mediaId가 없는 하자는 imageUrl이 null이어야 한다
        Company company = saveCompany("회사27");
        User owner = saveUser("owner27@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector27@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);
        Defect defectWithoutMedia = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED, null);

        mockMvc.perform(get("/api/inspections/{id}/defects", inspection.getId())
                .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(defectWithoutMedia.getId()))
                .andExpect(jsonPath("$.data[0].mediaId").isEmpty())
                .andExpect(jsonPath("$.data[0].imageUrl").isEmpty());
    }

    @Test
    void GET_삭제된하자제외_200() throws Exception {
        Company company = saveCompany("회사2");
        User owner = saveUser("owner2@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector2@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);
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
        Company company = saveCompany("회사3");
        User owner = saveUser("owner3@haja.com");
        addCompanyMembership(owner, company);
        User stranger = saveUser("stranger@haja.com");
        addCompanyMembership(stranger, saveCompany("회사3-외부"));
        User inspector = saveInspector("inspector3@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);
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
        addCompanyMembership(owner, saveCompany("회사4"));

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

    // ============ POST /api/inspections/{id}/defects 테스트 ============

    @Test
    void POST_정상생성_200() throws Exception {
        Company company = saveCompany("회사12");
        User owner = saveUser("owner12@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector12@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);

        DefectCreateRequest request = DefectCreateRequest.builder()
                .type(DefectType.CRACK)
                .grade(DefectGrade.B)
                .build();

        mockMvc.perform(post("/api/inspections/{id}/defects", inspection.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.type").value("CRACK"))
                .andExpect(jsonPath("$.data.grade").value("B"))
                .andExpect(jsonPath("$.data.status").value("DETECTED"))
                .andExpect(jsonPath("$.data.confidence").value(1.0))
                .andExpect(jsonPath("$.data.isReviewed").value(false));
    }

    @Test
    void POST_gradeNullable_200() throws Exception {
        Company company = saveCompany("회사13");
        User owner = saveUser("owner13@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector13@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);

        DefectCreateRequest request = DefectCreateRequest.builder()
                .type(DefectType.LEAK_EFFLORESCENCE)
                .build();

        mockMvc.perform(post("/api/inspections/{id}/defects", inspection.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("LEAK_EFFLORESCENCE"))
                .andExpect(jsonPath("$.data.grade").value((Object) null))
                .andExpect(jsonPath("$.data.isReviewed").value(false));
    }

    @Test
    void POST_bboxComplete_200() throws Exception {
        Company company = saveCompany("회사14");
        User owner = saveUser("owner14@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector14@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);

        DefectCreateRequest request = DefectCreateRequest.builder()
                .type(DefectType.CRACK)
                .bboxX(0.1)
                .bboxY(0.2)
                .bboxW(0.3)
                .bboxH(0.4)
                .grade(DefectGrade.A)
                .build();

        mockMvc.perform(post("/api/inspections/{id}/defects", inspection.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bboxX").value(0.1))
                .andExpect(jsonPath("$.data.bboxY").value(0.2))
                .andExpect(jsonPath("$.data.bboxW").value(0.3))
                .andExpect(jsonPath("$.data.bboxH").value(0.4));
    }

    @Test
    void POST_타인점검_404() throws Exception {
        Company company = saveCompany("회사15");
        User owner = saveUser("owner15@haja.com");
        addCompanyMembership(owner, company);
        User stranger = saveUser("stranger15@haja.com");
        addCompanyMembership(stranger, saveCompany("회사15-외부"));
        User inspector = saveInspector("inspector15@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);

        DefectCreateRequest request = DefectCreateRequest.builder()
                .type(DefectType.CRACK)
                .build();

        mockMvc.perform(post("/api/inspections/{id}/defects", inspection.getId())
                .with(csrf())
                .with(authentication(authOf(stranger)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INSPECTION_NOT_FOUND"));
    }

    @Test
    void POST_미존재점검_404() throws Exception {
        User owner = saveUser("owner16@haja.com");
        addCompanyMembership(owner, saveCompany("회사16"));

        DefectCreateRequest request = DefectCreateRequest.builder()
                .type(DefectType.CRACK)
                .build();

        mockMvc.perform(post("/api/inspections/{id}/defects", 999999L)
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INSPECTION_NOT_FOUND"));
    }

    @Test
    void POST_typeNull_400() throws Exception {
        Company company = saveCompany("회사17");
        User owner = saveUser("owner17@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector17@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);

        DefectCreateRequest request = DefectCreateRequest.builder()
                .build();

        mockMvc.perform(post("/api/inspections/{id}/defects", inspection.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void POST_bboxPartial_400() throws Exception {
        Company company = saveCompany("회사18");
        User owner = saveUser("owner18@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector18@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);

        // bboxX만 지정하고 나머지는 null
        DefectCreateRequest request = DefectCreateRequest.builder()
                .type(DefectType.CRACK)
                .bboxX(0.1)
                .build();

        mockMvc.perform(post("/api/inspections/{id}/defects", inspection.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void POST_bboxXOutOfRange_negative_400() throws Exception {
        Company company = saveCompany("회사20");
        User owner = saveUser("owner20@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector20@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);

        // bboxX=-1 (0.0 미만, 범위 위반) — 다른 3개는 valid (0.5)
        DefectCreateRequest request = DefectCreateRequest.builder()
                .type(DefectType.CRACK)
                .bboxX(-1.0)
                .bboxY(0.5)
                .bboxW(0.5)
                .bboxH(0.5)
                .build();

        mockMvc.perform(post("/api/inspections/{id}/defects", inspection.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void POST_bboxWOutOfRange_exceed_400() throws Exception {
        Company company = saveCompany("회사21");
        User owner = saveUser("owner21@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector21@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);

        // bboxW=1.5 (1.0 초과, 범위 위반) — 다른 3개는 valid (0.5)
        DefectCreateRequest request = DefectCreateRequest.builder()
                .type(DefectType.CRACK)
                .bboxX(0.5)
                .bboxY(0.5)
                .bboxW(1.5)
                .bboxH(0.5)
                .build();

        mockMvc.perform(post("/api/inspections/{id}/defects", inspection.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void POST_bboxYOutOfRange_negative_400() throws Exception {
        Company company = saveCompany("회사22");
        User owner = saveUser("owner22@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector22@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);

        // bboxY=-0.1 (0.0 미만, 범위 위반) — 다른 3개는 valid (0.5)
        DefectCreateRequest request = DefectCreateRequest.builder()
                .type(DefectType.CRACK)
                .bboxX(0.5)
                .bboxY(-0.1)
                .bboxW(0.5)
                .bboxH(0.5)
                .build();

        mockMvc.perform(post("/api/inspections/{id}/defects", inspection.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void POST_bboxHOutOfRange_exceed_400() throws Exception {
        Company company = saveCompany("회사23");
        User owner = saveUser("owner23@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector23@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);

        // bboxH=1.1 (1.0 초과, 범위 위반) — 다른 3개는 valid (0.5)
        DefectCreateRequest request = DefectCreateRequest.builder()
                .type(DefectType.CRACK)
                .bboxX(0.5)
                .bboxY(0.5)
                .bboxW(0.5)
                .bboxH(1.1)
                .build();

        mockMvc.perform(post("/api/inspections/{id}/defects", inspection.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void POST_bboxBoundaryValues_200() throws Exception {
        Company company = saveCompany("회사24");
        User owner = saveUser("owner24@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector24@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);

        // 경계값 0.0/1.0은 허용 범위 포함(inclusive)이라 정상 생성돼야 한다
        DefectCreateRequest request = DefectCreateRequest.builder()
                .type(DefectType.CRACK)
                .bboxX(0.0)
                .bboxY(1.0)
                .bboxW(0.0)
                .bboxH(1.0)
                .build();

        mockMvc.perform(post("/api/inspections/{id}/defects", inspection.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bboxX").value(0.0))
                .andExpect(jsonPath("$.data.bboxY").value(1.0))
                .andExpect(jsonPath("$.data.bboxW").value(0.0))
                .andExpect(jsonPath("$.data.bboxH").value(1.0));
    }

    @Test
    void POST_ANALYZING중이면_409_ANALYSIS_ALREADY_RUNNING() throws Exception {
        // 코드 리뷰 P1(머신 검수 2차) — ANALYZING 동안 수동 하자가 끼면, 워커가 첫 탐지 성공 시 호출하는
        // softDeleteAllForInspectionThenSave가 방금 추가된 이 하자까지 통째로 지운다. createManualDefect
        // 자체가 회차 상태를 막아야 이 TOCTOU가 닫힌다.
        Company company = saveCompany("회사25");
        User owner = saveUser("owner25@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector25@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = inspectionRepository.save(Inspection.builder()
                .facilityId(facility.getId())
                .createdBy(owner.getId())
                .assignedInspectorId(inspector.getId())
                .roundNo(1)
                .inspectionDate(java.time.LocalDate.now())
                .status(InspectionStatus.ANALYZING)
                .build());

        DefectCreateRequest request = DefectCreateRequest.builder()
                .type(DefectType.CRACK)
                .build();

        mockMvc.perform(post("/api/inspections/{id}/defects", inspection.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_ALREADY_RUNNING"));
    }

    @Test
    void POST_생성후조회_200() throws Exception {
        Company company = saveCompany("회사19");
        User owner = saveUser("owner19@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector19@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);

        DefectCreateRequest request = DefectCreateRequest.builder()
                .type(DefectType.REBAR_EXPOSURE)
                .grade(DefectGrade.C)
                .build();

        mockMvc.perform(post("/api/inspections/{id}/defects", inspection.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // 이어서 GET으로 조회해서 생성된 하자가 포함되는지 확인
        mockMvc.perform(get("/api/inspections/{id}/defects", inspection.getId())
                .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].type").value("REBAR_EXPOSURE"))
                .andExpect(jsonPath("$.data[0].grade").value("C"));
    }

    // ============ PATCH /api/defects/{id} 테스트 ============

    @Test
    void PATCH_등급변경_정상() throws Exception {
        Company company = saveCompany("회사5");
        User owner = saveUser("owner5@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector5@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.C, DefectStatus.DETECTED);

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .grade(DefectGrade.A)
                .reason("재검수 결과 A등급으로 상향")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(csrf())
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
        Company company = saveCompany("회사6");
        User owner = saveUser("owner6@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector6@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED);

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .deleted(true)
                .reason("오탐이므로 삭제")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void PATCH_gradeAndDeleted둘다_400() throws Exception {
        Company company = saveCompany("회사7");
        User owner = saveUser("owner7@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector7@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED);

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .grade(DefectGrade.A)
                .deleted(true)
                .reason("테스트")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void PATCH_둘다아님_400() throws Exception {
        Company company = saveCompany("회사8");
        User owner = saveUser("owner8@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector8@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED);

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .reason("테스트")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void PATCH_deletedFalse_400() throws Exception {
        Company company = saveCompany("회사9");
        User owner = saveUser("owner9@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector9@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED);

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .deleted(false)
                .reason("테스트")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void PATCH_reasonBlank_400() throws Exception {
        Company company = saveCompany("회사10");
        User owner = saveUser("owner10@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector10@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED);

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .grade(DefectGrade.A)
                .reason("")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void PATCH_RESOLVED상태_409() throws Exception {
        Company company = saveCompany("회사11");
        User owner = saveUser("owner11@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector11@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.C, DefectStatus.RESOLVED);

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .grade(DefectGrade.A)
                .reason("테스트")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void PATCH_이미삭제된하자_재삭제_409() throws Exception {
        // 이미 deleted=true인 하자에 isDeleted:true 재요청 시 409 회귀 테스트
        Company company = saveCompany("회사14");
        User owner = saveUser("owner14@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector14@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED);

        // 첫 번째 삭제 요청 — 200 성공
        DefectRevisionRequest deleteRequest = DefectRevisionRequest.builder()
                .deleted(true)
                .reason("오탐이므로 삭제")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(deleteRequest)))
                .andExpect(status().isOk());

        // 두 번째 재삭제 요청 — 409 INVALID_STATE_TRANSITION 기대
        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(deleteRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void PATCH_미존재하자_404() throws Exception {
        User owner = saveUser("owner12@haja.com");
        addCompanyMembership(owner, saveCompany("회사12"));

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .grade(DefectGrade.A)
                .reason("테스트")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", 999999L)
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEFECT_NOT_FOUND"));
    }

    @Test
    void PATCH_타인점검_404() throws Exception {
        Company company = saveCompany("회사13");
        User owner = saveUser("owner13@haja.com");
        addCompanyMembership(owner, company);
        User stranger = saveUser("stranger2@haja.com");
        addCompanyMembership(stranger, saveCompany("회사13-외부"));
        User inspector = saveInspector("inspector13@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED);

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .grade(DefectGrade.A)
                .reason("테스트")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(csrf())
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
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}
