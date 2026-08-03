package com.hajacheck.core.defect.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.Map;
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
    private com.hajacheck.core.defect.service.DefectWriter defectWriter;
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

    /**
     * 영속성 컨텍스트를 DB로 밀어낸 뒤 defect_revisions 행을 생성순으로 읽는다(UT-22).
     * 응답 body가 아니라 실제 테이블을 보는 이유: 이력 기록이 통째로 빠지거나 soft delete가
     * hard delete로 바뀌어도 body 검증만으로는 그대로 통과하기 때문.
     */
    private List<Map<String, Object>> revisionsOf(Long defectId) {
        defectRepository.flush();
        return jdbcTemplate.queryForList(
                "SELECT revised_by, field_changed, old_value, new_value, reason, created_at "
                        + "FROM defect_revisions WHERE defect_id = ? ORDER BY id", defectId);
    }

    private Map<String, Object> defectRowOf(Long defectId) {
        defectRepository.flush();
        return jdbcTemplate.queryForMap(
                "SELECT grade, is_reviewed, is_deleted FROM defects WHERE id = ?", defectId);
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
                .andExpect(jsonPath("$.data[0].typeLabel").value("균열"))
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
        Media media = saveMedia(inspection);

        DefectCreateRequest request = DefectCreateRequest.builder()
                .type(DefectType.CRACK)
                .bboxX(0.1)
                .bboxY(0.2)
                .bboxW(0.3)
                .bboxH(0.4)
                .mediaId(media.getId())
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
                .andExpect(jsonPath("$.data.bboxH").value(0.4))
                .andExpect(jsonPath("$.data.mediaId").value(media.getId()));
    }

    @Test
    void POST_bboxCompleteButMediaIdNull_400() throws Exception {
        // bbox 4개가 모두 지정되면 mediaId도 필수
        Company company = saveCompany("회사823");
        User owner = saveUser("owner823@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector823@haja.com", company);
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
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
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
        Media media = saveMedia(inspection);

        // 경계값 0.0/1.0은 허용 범위 포함(inclusive)이라 정상 생성돼야 한다
        DefectCreateRequest request = DefectCreateRequest.builder()
                .type(DefectType.CRACK)
                .bboxX(0.0)
                .bboxY(1.0)
                .bboxW(0.0)
                .bboxH(1.0)
                .mediaId(media.getId())
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
                .andExpect(jsonPath("$.data.bboxH").value(1.0))
                .andExpect(jsonPath("$.data.mediaId").value(media.getId()));
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
    void POST_FAILED회차면_409_DEFECT_WRITE_BLOCKED_ANALYSIS_FAILED() throws Exception {
        // PR머신 리뷰 3차 P1 — FAILED 재분석의 원자적 선점은 "비삭제 하자 없음" 요건을 FAILED에 한해
        // 건너뛰는데, 그 전제("FAILED에 남은 하자는 전부 이번에 실패한 실행의 AI 결과뿐")가 성립하려면
        // FAILED에서 수동 하자 추가 자체를 막아야 한다. 안 막으면 사람이 FAILED 회차에 등록한 하자가
        // 재분석 워커의 softDeleteAllForInspectionThenSave(비삭제 하자 전체 대상)에 휩쓸려 무보상
        // 유실된다 — 위 ANALYZING 테스트와 동일한 이유, 다른 상태.
        Company company = saveCompany("회사26");
        User owner = saveUser("owner26@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector26@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = inspectionRepository.save(Inspection.builder()
                .facilityId(facility.getId())
                .createdBy(owner.getId())
                .assignedInspectorId(inspector.getId())
                .roundNo(1)
                .inspectionDate(java.time.LocalDate.now())
                .status(InspectionStatus.FAILED)
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
                .andExpect(jsonPath("$.error.code").value("DEFECT_WRITE_BLOCKED_ANALYSIS_FAILED"));
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

        // UT-22 — 이력 1건이 "누가·언제·무엇"까지 실제로 기록됐는지 DB에서 확인
        List<Map<String, Object>> revisions = revisionsOf(defect.getId());
        assertThat(revisions).hasSize(1);
        Map<String, Object> revision = revisions.get(0);
        assertThat(revision.get("revised_by")).isEqualTo(owner.getId());
        assertThat(revision.get("field_changed")).isEqualTo("grade");
        assertThat(revision.get("old_value")).isEqualTo("C");
        assertThat(revision.get("new_value")).isEqualTo("A");
        assertThat(revision.get("reason")).isEqualTo("재검수 결과 A등급으로 상향");
        assertThat(revision.get("created_at")).isNotNull();

        // 원본은 최신값으로 갱신(이력만 쌓고 본체가 안 바뀌는 경우 방지)
        Map<String, Object> defectRow = defectRowOf(defect.getId());
        assertThat(defectRow.get("grade")).hasToString("A");
        assertThat(defectRow.get("is_reviewed")).isEqualTo(true);
        assertThat(defectRow.get("is_deleted")).isEqualTo(false);
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

        // UT-22 — 삭제도 이력에 남는다
        List<Map<String, Object>> revisions = revisionsOf(defect.getId());
        assertThat(revisions).hasSize(1);
        Map<String, Object> revision = revisions.get(0);
        assertThat(revision.get("revised_by")).isEqualTo(owner.getId());
        assertThat(revision.get("field_changed")).isEqualTo("is_deleted");
        assertThat(revision.get("old_value")).isEqualTo("false");
        assertThat(revision.get("new_value")).isEqualTo("true");
        assertThat(revision.get("reason")).isEqualTo("오탐이므로 삭제");

        // Soft Delete — 행 자체는 남고 is_deleted 플래그만 선다(hard delete로 바뀌면 여기서 걸린다)
        Map<String, Object> defectRow = defectRowOf(defect.getId());
        assertThat(defectRow.get("is_deleted")).isEqualTo(true);
        assertThat(defectRow.get("grade")).hasToString("B");  // 삭제는 등급을 건드리지 않는다
    }

    @Test
    void PATCH_FAILED회차하자면_409_DEFECT_WRITE_BLOCKED_ANALYSIS_FAILED() throws Exception {
        // PR머신 리뷰 4차 P1 — FAILED 재분석 바이패스의 전제("FAILED에 남은 하자는 전부 이번에 실패한
        // 실행이 만든 AI 결과뿐")를 지키려면 createManualDefect(POST)뿐 아니라 reviewDefect(PATCH,
        // 등급 조정·오탐 삭제·오탐 복구)도 막아야 한다. 안 막으면 사람이 FAILED 회차 하자를 큐레이션한
        // 뒤 재분석했을 때 워커의 softDeleteAllForInspectionThenSave가 그 큐레이션을 무보상으로 지운다.
        Company company = saveCompany("회사29");
        User owner = saveUser("owner29@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector29@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = inspectionRepository.save(Inspection.builder()
                .facilityId(facility.getId())
                .createdBy(owner.getId())
                .assignedInspectorId(inspector.getId())
                .roundNo(1)
                .inspectionDate(java.time.LocalDate.now())
                .status(InspectionStatus.FAILED)
                .build());
        Defect defect = saveDefect(inspection, DefectGrade.C, DefectStatus.DETECTED);

        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .grade(DefectGrade.A)
                .reason("등급 상향 시도")
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defect.getId())
                .with(csrf())
                .with(authentication(authOf(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEFECT_WRITE_BLOCKED_ANALYSIS_FAILED"));
    }

    @Test
    void PATCH_검수이력_append_only_누적() throws Exception {
        // UT-22 핵심 — 같은 하자를 연속 검수하면 이력이 덮어써지지 않고 누적되고,
        // 먼저 기록된 행은 이후 검수에도 그대로 보존된다(append-only).
        Company company = saveCompany("회사28");
        User owner = saveUser("owner28@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector28@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.C, DefectStatus.DETECTED);

        reviewGrade(defect.getId(), owner, DefectGrade.B, "1차 검수 — B로 하향");
        List<Map<String, Object>> afterFirst = revisionsOf(defect.getId());
        assertThat(afterFirst).hasSize(1);
        Map<String, Object> firstRevision = afterFirst.get(0);

        reviewGrade(defect.getId(), owner, DefectGrade.D, "2차 검수 — D로 상향");

        List<Map<String, Object>> afterSecond = revisionsOf(defect.getId());
        assertThat(afterSecond).hasSize(2);
        // 1행은 그대로(수정·덮어쓰기 없음), 2행의 old_value는 1행의 new_value를 이어받는다
        assertThat(afterSecond.get(0)).isEqualTo(firstRevision);
        assertThat(afterSecond.get(1).get("old_value")).isEqualTo("B");
        assertThat(afterSecond.get(1).get("new_value")).isEqualTo("D");

        // 원본은 항상 마지막 검수 결과만 보유
        assertThat(defectRowOf(defect.getId()).get("grade")).hasToString("D");
    }

    private void reviewGrade(Long defectId, User reviewer, DefectGrade grade, String reason) throws Exception {
        DefectRevisionRequest request = DefectRevisionRequest.builder()
                .grade(grade)
                .reason(reason)
                .build();

        mockMvc.perform(patch("/api/defects/{id}", defectId)
                .with(csrf())
                .with(authentication(authOf(reviewer)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
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
    void PATCH_삭제되지않은하자_복구요청_409() throws Exception {
        // #1399 — isDeleted=false는 이제 '복구'로 유효하다(예전엔 무조건 400). 다만 삭제된 적 없는
        // 하자를 되살리는 건 의미가 없어 중복 삭제와 같은 INVALID_STATE_TRANSITION으로 막는다.
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
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void PATCH_오탐복구_정상_이력도남는다() throws Exception {
        // #1399 — 삭제 → 복구 왕복. soft delete라 데이터가 그대로 살아 있어 플래그만 되돌리면 되고,
        // 삭제 사유 이력은 append-only라 보존된 채 복구 이력이 한 줄 더 쌓인다.
        Company company = saveCompany("회사9-2");
        User owner = saveUser("owner9-2@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector9-2@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED);

        patchDefect(owner, defect.getId(), DefectRevisionRequest.builder()
                .deleted(true).reason("오탐이라 삭제").build())
                .andExpect(status().isOk());
        assertThat(defectRowOf(defect.getId()).get("is_deleted")).isEqualTo(true);

        patchDefect(owner, defect.getId(), DefectRevisionRequest.builder()
                .deleted(false).reason("확인해보니 실제 하자였음").build())
                .andExpect(status().isOk());

        assertThat(defectRowOf(defect.getId()).get("is_deleted")).isEqualTo(false);
        // 복구된 하자는 일반 목록 조회에 다시 나타난다(뷰어에서 검수를 이어갈 수 있어야 한다).
        mockMvc.perform(get("/api/inspections/{id}/defects", inspection.getId())
                .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        // 삭제 이력이 지워지지 않고 복구 이력이 추가된다(append-only).
        List<Map<String, Object>> revisions = revisionsOf(defect.getId());
        assertThat(revisions).hasSize(2);
        assertThat(revisions).anySatisfy(r -> {
            assertThat(r.get("field_changed")).isEqualTo("is_deleted");
            assertThat(r.get("old_value")).isEqualTo("false");
            assertThat(r.get("new_value")).isEqualTo("true");
            assertThat(r.get("reason")).isEqualTo("오탐이라 삭제");
        });
        assertThat(revisions).anySatisfy(r -> {
            assertThat(r.get("field_changed")).isEqualTo("is_deleted");
            assertThat(r.get("old_value")).isEqualTo("true");
            assertThat(r.get("new_value")).isEqualTo("false");
            assertThat(r.get("reason")).isEqualTo("확인해보니 실제 하자였음");
        });
    }

    @Test
    void PATCH_재분석소프트삭제분은_복구불가_409() throws Exception {
        // #1399 핵심 가드 — is_deleted=true 에는 ⓐ 검수자 오탐 판정과 ⓑ 재분석 때 통째로 밀린
        // 구버전이 섞여 있다. ⓑ에는 is_deleted 이력이 없고, 되살리면 이미 대체된 유령 하자가
        // 화면·통계에 부활한다. 목록에 안 뜨는 id로 직접 요청이 들어와도 막혀야 한다.
        Company company = saveCompany("회사9-3");
        User owner = saveUser("owner9-3@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector9-3@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);
        Defect defect = saveDefect(inspection, DefectGrade.B, DefectStatus.DETECTED);
        // 이력 없이 플래그만 세운다 = DefectWriter.softDeleteAllForInspectionThenSave 와 동일한 상태
        defect.softDelete();
        defectRepository.save(defect);

        patchDefect(owner, defect.getId(), DefectRevisionRequest.builder()
                .deleted(false).reason("되살리기 시도").build())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));

        assertThat(defectRowOf(defect.getId()).get("is_deleted")).isEqualTo(true);
    }

    @Test
    void GET_삭제된하자목록_사유와삭제자를함께반환하고_재분석삭제분은제외() throws Exception {
        // #1399 — 삭제 사유는 저장돼 있었으나 모든 조회가 is_deleted=false 필터라 어느 화면에서도
        // 읽을 수 없었다. 이 엔드포인트가 그 사각지대를 메운다.
        Company company = saveCompany("회사9-4");
        User owner = saveUser("owner9-4@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector9-4@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);

        Defect reviewerDeleted = saveDefect(inspection, DefectGrade.C, DefectStatus.DETECTED);
        patchDefect(owner, reviewerDeleted.getId(), DefectRevisionRequest.builder()
                .deleted(true).reason("그림자를 균열로 오인").build())
                .andExpect(status().isOk());

        Defect reanalysisDeleted = saveDefect(inspection, DefectGrade.D, DefectStatus.DETECTED);
        reanalysisDeleted.softDelete();  // 이력 없는 삭제 = 재분석 소프트삭제
        defectRepository.save(reanalysisDeleted);

        saveDefect(inspection, DefectGrade.A, DefectStatus.DETECTED);  // 살아있는 하자

        mockMvc.perform(get("/api/inspections/{id}/defects/deleted", inspection.getId())
                .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].defect.id").value(reviewerDeleted.getId()))
                .andExpect(jsonPath("$.data[0].deletedReason").value("그림자를 균열로 오인"))
                .andExpect(jsonPath("$.data[0].deletedByName").value(owner.getName()))
                .andExpect(jsonPath("$.data[0].deletedAt").isNotEmpty());
    }

    @Test
    void GET_삭제된하자목록_타회사는404() throws Exception {
        // 미존재와 타인 소유를 구분하지 않는 기존 원칙(IDOR 방지)을 신규 엔드포인트에도 적용한다.
        Company ownerCompany = saveCompany("회사9-5");
        User owner = saveUser("owner9-5@haja.com");
        addCompanyMembership(owner, ownerCompany);
        User inspector = saveInspector("inspector9-5@haja.com", ownerCompany);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);

        Company otherCompany = saveCompany("타회사9-5");
        User outsider = saveUser("outsider9-5@haja.com");
        addCompanyMembership(outsider, otherCompany);

        mockMvc.perform(get("/api/inspections/{id}/defects/deleted", inspection.getId())
                .with(authentication(authOf(outsider))))
                .andExpect(status().isNotFound());
    }

    @Test
    void 전량_오탐삭제후_재분석하면_구회차_삭제분은_되살리기_목록에서_빠진다() throws Exception {
        // #1401 — 재분석은 비삭제분만 소프트삭제하므로 검수자가 이미 지운 하자는 is_deleted 이력을
        // 그대로 유지한다. 재분석 게이트가 '비삭제 0건이면 허용'(InspectionAnalysisService)이라
        // '전부 오탐 삭제 → 재분석' 시퀀스가 성립하고, 세대 마커가 없으면 대체된 구회차 삭제분이
        // 되살리기 후보로 남아 되살릴 때 유령 하자가 부활한다.
        Company company = saveCompany("회사9-6");
        User owner = saveUser("owner9-6@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector9-6@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);

        Defect first = saveDefect(inspection, DefectGrade.C, DefectStatus.DETECTED);
        Defect second = saveDefect(inspection, DefectGrade.D, DefectStatus.DETECTED);
        patchDefect(owner, first.getId(), DefectRevisionRequest.builder()
                .deleted(true).reason("전부 오탐이라 삭제 1").build()).andExpect(status().isOk());
        patchDefect(owner, second.getId(), DefectRevisionRequest.builder()
                .deleted(true).reason("전부 오탐이라 삭제 2").build()).andExpect(status().isOk());

        // 이 시점에는 둘 다 되살릴 수 있어야 한다(아직 재분석 전).
        mockMvc.perform(get("/api/inspections/{id}/defects/deleted", inspection.getId())
                .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        // 재분석 경로 재현 — 워커가 실제로 호출하는 DefectWriter 를 그대로 쓴다.
        Defect reanalyzed = Defect.builder()
                .inspectionId(inspection.getId())
                .type(DefectType.CRACK)
                .confidence(0.9)
                .grade(DefectGrade.B)
                .build();
        defectWriter.softDeleteAllForInspectionThenSave(
                owner.getId(), inspection.getId(), List.of(reanalyzed));

        // 구회차 삭제분은 목록에서 빠진다.
        mockMvc.perform(get("/api/inspections/{id}/defects/deleted", inspection.getId())
                .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // 목록에 안 떠도 id 를 알면 직접 요청할 수 있으므로 복구 자체도 막혀야 한다.
        patchDefect(owner, first.getId(), DefectRevisionRequest.builder()
                .deleted(false).reason("되살리기 시도").build())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));

        // 부활하지 않았음을 일반 목록으로 확인 — 재분석 결과 1건만 보여야 한다.
        mockMvc.perform(get("/api/inspections/{id}/defects", inspection.getId())
                .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(reanalyzed.getId()));
    }

    @Test
    void 재분석_이전에_삭제한하자는_같은세대라면_되살릴수있다() throws Exception {
        // #1401 가드가 과하게 걸려 정상 케이스까지 막지 않는지 고정한다 — 일부만 오탐 삭제한
        // 상태(재분석은 비삭제분이 남아 있어 애초에 거부된다)에서는 그대로 되살릴 수 있어야 한다.
        Company company = saveCompany("회사9-7");
        User owner = saveUser("owner9-7@haja.com");
        addCompanyMembership(owner, company);
        User inspector = saveInspector("inspector9-7@haja.com", company);
        Facility facility = saveFacility(owner);
        Inspection inspection = saveInspection(facility, owner, inspector);

        Defect deleted = saveDefect(inspection, DefectGrade.C, DefectStatus.DETECTED);
        saveDefect(inspection, DefectGrade.A, DefectStatus.DETECTED);  // 살아있는 하자
        patchDefect(owner, deleted.getId(), DefectRevisionRequest.builder()
                .deleted(true).reason("이건 오탐").build()).andExpect(status().isOk());

        patchDefect(owner, deleted.getId(), DefectRevisionRequest.builder()
                .deleted(false).reason("다시 보니 진짜 하자").build())
                .andExpect(status().isOk());

        assertThat(defectRowOf(deleted.getId()).get("is_deleted")).isEqualTo(false);
    }

    private org.springframework.test.web.servlet.ResultActions patchDefect(
            User actor, Long defectId, DefectRevisionRequest request) throws Exception {
        return mockMvc.perform(patch("/api/defects/{id}", defectId)
                .with(csrf())
                .with(authentication(authOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
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
