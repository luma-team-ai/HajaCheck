package com.hajacheck.core.report.controller;

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
                .ownerId(owner.getId())
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
}
