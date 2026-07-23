package com.hajacheck.core.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.report.dto.UpdateReportContentRequest;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.repository.ReportRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 보고서 API 소유권·상태코드 통합 테스트(#446). AI 서버 호출(generateDraft)은 외부 FastAPI 의존이라
 * 이 테스트 범위 밖 — 이미 저장된 보고서를 대상으로 하는 조회/수정/확정 엔드포인트만 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportControllerTest extends PostgresTestSupport {

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
    private ReportRepository reportRepository;

    @AfterEach
    void tearDown() {
        reportRepository.deleteAll();
        inspectionRepository.deleteAll();
        facilityRepository.deleteAll();
        companyMembershipRepository.deleteAll();
        java.util.List<User> users = userRepository.findAll();
        users.forEach(u -> u.assignToCompany(null));
        userRepository.saveAll(users);
        companyRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User seedOwner(String email) {
        User owner = userRepository.save(User.builder()
                .email(email)
                .name("소유자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), "보고서테스트회사", "REG-" + owner.getId(), "대표자",
                "서울시", null, "https://files.example/business.pdf", "{}"));
        company.markBusinessVerified();
        company.approve(owner.getId());
        companyRepository.save(company);
        companyMembershipRepository.save(CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        owner.assignToCompany(company.getId());
        return userRepository.save(owner);
    }

    private Inspection seedInspection(User owner) {
        Facility facility = facilityRepository.save(Facility.builder()
                .companyId(owner.getCompanyId())
                .name("테스트빌딩")
                .type("BUILDING")
                .address("서울시 강남구")
                .build());
        return inspectionRepository.save(Inspection.builder()
                .facilityId(facility.getId())
                .createdBy(owner.getId())
                .assignedInspectorId(owner.getId())
                .roundNo(1)
                .inspectionDate(LocalDate.now())
                .status(InspectionStatus.CREATED)
                .build());
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void 상세조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/reports/{id}", 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 목록조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/inspections/{id}/reports", 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 상세조회_존재하지않는보고서_404() throws Exception {
        User owner = seedOwner("no-report-owner@haja.com");

        mockMvc.perform(get("/api/reports/{id}", 999L).with(authentication(authOf(owner))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("REPORT_NOT_FOUND"));
    }

    @Test
    void 상세조회_본인소유_200과콘텐츠반환() throws Exception {
        User owner = seedOwner("report-owner@haja.com");
        Inspection inspection = seedInspection(owner);
        Report report = reportRepository.save(
                Report.draft(inspection.getId(), 1, "{\"summary\":\"점검 결과\"}", owner.getId()));

        mockMvc.perform(get("/api/reports/{id}", report.getId()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(report.getId()))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.content.summary").value("점검 결과"));
    }

    @Test
    void 상세조회_타인소유_존재하지않는것과동일하게404() throws Exception {
        User owner = seedOwner("report-owner2@haja.com");
        User stranger = seedOwner("report-stranger@haja.com");
        Inspection inspection = seedInspection(owner);
        Report report = reportRepository.save(Report.draft(inspection.getId(), 1, "{}", owner.getId()));

        mockMvc.perform(get("/api/reports/{id}", report.getId()).with(authentication(authOf(stranger))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("REPORT_NOT_FOUND"));
    }

    @Test
    void 목록조회_본인소유_버전목록을최신순으로반환() throws Exception {
        User owner = seedOwner("report-owner3@haja.com");
        Inspection inspection = seedInspection(owner);
        reportRepository.save(Report.draft(inspection.getId(), 1, "{}", owner.getId()));
        reportRepository.save(Report.draft(inspection.getId(), 2, "{}", owner.getId()));

        mockMvc.perform(get("/api/inspections/{id}/reports", inspection.getId())
                        .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].version").value(2));
    }

    @Test
    void 본문수정_DRAFT상태_200과grounding필드리셋() throws Exception {
        User owner = seedOwner("report-owner4@haja.com");
        Inspection inspection = seedInspection(owner);
        Report report = reportRepository.save(Report.draft(inspection.getId(), 1, "{\"a\":1}", owner.getId()));
        String body = objectMapper.writeValueAsString(new UpdateReportContentRequest("{\"a\":2}"));

        mockMvc.perform(patch("/api/reports/{id}", report.getId())
                        .with(csrf())
                        .with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.a").value(2))
                .andExpect(jsonPath("$.data.groundingCheckPassed").doesNotExist());
    }

    @Test
    void 본문수정_타인소유_404() throws Exception {
        User owner = seedOwner("report-owner5@haja.com");
        User stranger = seedOwner("report-stranger2@haja.com");
        Inspection inspection = seedInspection(owner);
        Report report = reportRepository.save(Report.draft(inspection.getId(), 1, "{}", owner.getId()));
        String body = objectMapper.writeValueAsString(new UpdateReportContentRequest("{\"a\":2}"));

        mockMvc.perform(patch("/api/reports/{id}", report.getId())
                        .with(csrf())
                        .with(authentication(authOf(stranger)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("REPORT_NOT_FOUND"));
    }

    @Test
    void 초안생성_미인증_401() throws Exception {
        mockMvc.perform(post("/api/inspections/{id}/reports", 1L).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * PDF 업로드(POST /api/reports/{id}/pdf) 후 반환된 pdfUrl 을 실제로 GET 해서 200 과 원본 바이트가
     * 서빙되는지 검증(PR #455 P2-1). 정적 리소스 핸들러가 아니라 소유권 검증을 거치는
     * GET /api/reports/{id}/pdf/{storageKey} 다운로드 엔드포인트를 사용한다.
     */
    @Test
    void PDF업로드후_pdfUrl로_GET하면_200과원본바이트() throws Exception {
        User owner = seedOwner("report-pdf-owner@haja.com");
        Inspection inspection = seedInspection(owner);
        Report report = reportRepository.save(Report.draft(inspection.getId(), 1, "{}", owner.getId()));
        byte[] pdfBytes = "%PDF-1.4 test-report-body".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);

        String uploadResponse = mockMvc.perform(multipart("/api/reports/{id}/pdf", report.getId())
                        .file(file)
                        .with(csrf())
                        .with(authentication(authOf(owner))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pdfUrl").exists())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(uploadResponse);
        String pdfUrl = json.path("data").path("pdfUrl").asText();
        assertThat(pdfUrl).startsWith("/api/reports/%d/pdf/".formatted(report.getId()));

        mockMvc.perform(get(pdfUrl).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(content().bytes(pdfBytes));
    }

    /**
     * 업로드한 PDF를 타인이 pdfUrl만 알고 GET 시도하면 404 — 정적 리소스 서빙 시절에는 걸리지 않던
     * 소유권 검증이 다운로드 엔드포인트 전환(#455 P2-1)으로 적용되는지 확인하는 핵심 회귀 테스트다.
     */
    @Test
    void PDF업로드후_타인이pdfUrl로_GET하면_404() throws Exception {
        User owner = seedOwner("report-pdf-owner2@haja.com");
        User stranger = seedOwner("report-pdf-stranger@haja.com");
        Inspection inspection = seedInspection(owner);
        Report report = reportRepository.save(Report.draft(inspection.getId(), 1, "{}", owner.getId()));
        byte[] pdfBytes = "%PDF-1.4 test-report-body".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);

        String uploadResponse = mockMvc.perform(multipart("/api/reports/{id}/pdf", report.getId())
                        .file(file)
                        .with(csrf())
                        .with(authentication(authOf(owner))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(uploadResponse);
        String pdfUrl = json.path("data").path("pdfUrl").asText();

        mockMvc.perform(get(pdfUrl).with(authentication(authOf(stranger))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("REPORT_NOT_FOUND"));
    }

    /**
     * content-type 헤더는 application/pdf 지만 실제 바이트가 PDF 매직넘버(%PDF-)로 시작하지 않으면
     * 거부한다(#455 P2-3, content-type 헤더 스푸핑 방지).
     */
    @Test
    void PDF업로드_매직넘버아닌바이트면_FILE_INVALID_TYPE() throws Exception {
        User owner = seedOwner("report-pdf-owner3@haja.com");
        Inspection inspection = seedInspection(owner);
        Report report = reportRepository.save(Report.draft(inspection.getId(), 1, "{}", owner.getId()));
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.pdf", MediaType.APPLICATION_PDF_VALUE, "not-a-real-pdf-body".getBytes());

        mockMvc.perform(multipart("/api/reports/{id}/pdf", report.getId())
                        .file(file)
                        .with(csrf())
                        .with(authentication(authOf(owner))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FILE_INVALID_TYPE"));
    }

    /**
     * finalize 요청의 pdfUrl 이 이 보고서의 업로드 엔드포인트 형식을 따르지 않으면 거부한다
     * (#455 P2-2, 임의 문자열/타 보고서 pdfUrl 로 확정 시도 차단).
     */
    @Test
    void 확정_임의문자열pdfUrl이면_REPORT_PDF_URL_INVALID() throws Exception {
        User owner = seedOwner("report-finalize-owner@haja.com");
        Inspection inspection = seedInspection(owner);
        Report report = reportRepository.save(Report.draft(inspection.getId(), 1, "{}", owner.getId()));
        report.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                report.captureGroundingRequestContext(), report.getContentJson()),
                        null),
                owner.getId());
        reportRepository.save(report);

        String body = objectMapper.writeValueAsString(
                new com.hajacheck.core.report.dto.FinalizeReportRequest("https://evil.example/r.pdf"));

        mockMvc.perform(post("/api/reports/{id}/finalize", report.getId())
                        .with(csrf())
                        .with(authentication(authOf(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REPORT_PDF_URL_INVALID"));
    }

    /**
     * B가 자신이 소유한 report_B.id와 A가 업로드한 report_A의 storageKey를 조합해
     * GET /api/reports/{report_B.id}/pdf/{storageKey_of_A} 호출 시 404 (IDOR 및 storageKey 혼용 차단 — P1 필수 회귀 테스트).
     */
    @Test
    void PDF다운로드_본인reportId와_타인storageKey조합시_404() throws Exception {
        User userA = seedOwner("report-pdf-ownerA@haja.com");
        User userB = seedOwner("report-pdf-ownerB@haja.com");

        Inspection inspectionA = seedInspection(userA);
        Inspection inspectionB = seedInspection(userB);

        Report reportA = reportRepository.save(Report.draft(inspectionA.getId(), 1, "{}", userA.getId()));
        Report reportB = reportRepository.save(Report.draft(inspectionB.getId(), 1, "{}", userB.getId()));

        byte[] pdfBytesA = "%PDF-1.4 test-report-body-A".getBytes();
        MockMultipartFile fileA = new MockMultipartFile(
                "file", "reportA.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytesA);

        // A가 자신의 보고서 reportA에 PDF 업로드
        String uploadResponseA = mockMvc.perform(multipart("/api/reports/{id}/pdf", reportA.getId())
                        .file(fileA)
                        .with(csrf())
                        .with(authentication(authOf(userA))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode jsonA = objectMapper.readTree(uploadResponseA);
        String pdfUrlA = jsonA.path("data").path("pdfUrl").asText();
        String storageKeyA = pdfUrlA.substring(pdfUrlA.lastIndexOf('/') + 1);

        // B가 자신의 보고서 reportB.getId() + A의 storageKeyA로 조합하여 다운로드 시도 -> 404
        mockMvc.perform(get("/api/reports/{id}/pdf/{storageKey}", reportB.getId(), storageKeyA)
                        .with(authentication(authOf(userB))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FILE_NOT_FOUND"));
    }

    /**
     * 동일 inspectionId + version으로 DB에 두 번 저장 시도 시 실제 PostgreSQL uk_reports_inspection_version
     * 유니크 제약 충돌 예외가 발생하고 GlobalExceptionHandler가 409 REPORT_VERSION_CONFLICT 로 매핑하는지 검증 (P2).
     */
    @Test
    void 동일_inspectionId와_version으로_중복저장시_실제DB제약충돌로_REPORT_VERSION_CONFLICT_409매핑() {
        User owner = seedOwner("report-dup-owner@haja.com");
        Inspection inspection = seedInspection(owner);

        reportRepository.saveAndFlush(Report.draft(inspection.getId(), 1, "{}", owner.getId()));

        org.springframework.dao.DataIntegrityViolationException ex =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> reportRepository.saveAndFlush(Report.draft(inspection.getId(), 1, "{}", owner.getId())),
                        org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(ex).isNotNull();

        com.hajacheck.global.exception.GlobalExceptionHandler handler =
                new com.hajacheck.global.exception.GlobalExceptionHandler();
        org.springframework.http.ResponseEntity<com.hajacheck.global.common.ApiResponse<Void>> response =
                handler.handleDataIntegrityViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(response.getBody().error().code()).isEqualTo("REPORT_VERSION_CONFLICT");
    }
}
