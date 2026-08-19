package com.hajacheck.core.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.dto.InspectionCreateRequest;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.entity.ReportStatus;
import com.hajacheck.core.report.repository.ReportRepository;
import com.hajacheck.core.report.support.ReportPdfStorage;
import com.hajacheck.support.PostgresTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

/**
 * 락 순서 역전(ABBA) 데드락 회귀선(#1702 리뷰 P1) — 실 PostgreSQL(Testcontainers).
 *
 * <p><b>무엇을 막는가</b>: {@link InspectionService#createInspection}의 소급 회차 경로는
 * {@code facilities}(FOR UPDATE) → {@code inspections}(회차 시프트) → {@code reports}(DRAFT 스냅샷
 * 재동기화) 순으로 락을 잡는다. 한편 {@link ReportService#finalizeReport}는 {@code reports}(더티 체킹)
 * → {@code inspections}(회차 상태 전이) → {@code facilities}(다음 점검일 재계산) 순으로 잡는다.
 * 같은 시설물에 두 요청이 동시에 오면 순환이 완성되어 PostgreSQL이 {@code deadlock_timeout} 후 한쪽을
 * {@code 40P01}로 abort시킨다 — 사용자에겐 원인 불명의 500이다.
 *
 * <p>수정은 40P01 재시도가 아니라 <b>순서 통일</b>이다: finalize가 첫 쓰기보다 먼저 시설물 행을 잠가
 * {@code facilities → inspections → reports} 하나로 맞춘다. 시설물 행이 단일 게이트가 되므로 두 작업은
 * 데드락 대신 <b>직렬화</b>된다. 이 테스트는 두 작업을 같은 시설물에 동시에 부딪혀 예외 없이 모두
 * 성공하는지 확인한다 — 순서가 다시 역전되면 여기서 락 획득 실패로 드러난다.
 *
 * <p>⚠️ 의도적으로 클래스 레벨 {@code @Transactional}을 붙이지 않는다 — 두 서비스 메서드가 각각 독립된
 * 실 트랜잭션을 얻어야 진짜 락 경쟁이 재현된다. 대신 커밋된 데이터를 {@link #tearDown()}에서 정리한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportFinalizeRoundReorderDeadlockIntegrationTest extends PostgresTestSupport {

    // ⚠️ @MockBean 을 쓰지 않는다 — 목 빈은 별도 컨텍스트 캐시 키를 만들어 Spring 컨텍스트(와 Hikari
    // 풀)를 하나 더 띄우고, 공용 Testcontainers PostgreSQL 이 "too many clients" 로 다른 테스트까지
    // 무너뜨린다. 실제 저장소에 진짜 PDF 한 장을 넣어 두는 편이 싸고 부작용이 없다.
    @Autowired
    private ReportPdfStorage reportPdfStorage;

    @Autowired
    private InspectionService inspectionService;
    @Autowired
    private ReportService reportService;
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
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int ROUNDS = 6;

    /** overview·summary만 활성화해 상세/권고 필수 항목 검증과 상세 grounding 비교를 함께 건너뛴다. */
    private static final String FINALIZABLE_CONTENT_JSON = """
            {"overview":{"purpose":"정기 점검","facility_summary":"철근콘크리트 5층","scope":"외벽 전면"},
             "summary":{"overall_opinion":"양호","total_count":0,"grade_distribution":{},"key_findings":[]},
             "detail":{"items":[]},
             "recommendation":{"items":[],"priorities":[]},
             "reportOptions":{"sections":["overview","summary"],"includePhoto":true}}
            """;

    private final Map<Long, String> storageKeyByReportId = new ConcurrentHashMap<>();

    private Long ownerId;
    private Long companyId;
    private Long facilityId;
    private Long ownerMembershipId;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(User.builder()
                .email("deadlock-owner-" + System.nanoTime() + "@haja.com")
                .name("데드락테스트소유자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build());

        // business_registration_number 는 varchar(20) — 접두사 짧게 + nanoTime 뒷자리로 유니크성 확보.
        String brn = "brn-" + (System.nanoTime() % 10_000_000_000L);
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)데드락테스트", brn,
                "김대표", "서울시 강남구", null, "http://files/brn.png", "{}"));
        company.markBusinessVerified();
        company.approve(owner.getId());
        company = companyRepository.save(company);
        owner.assignToCompany(company.getId());
        userRepository.save(owner);
        CompanyMembership membership = companyMembershipRepository.save(
                CompanyMembership.approvedOwner(company.getId(), owner.getId()));

        // inspectionCycleMonths 가 있어야 finalize 가 실제로 facilities 를 UPDATE 한다
        // (없으면 recalculateNextInspectionDueAt 이 조기 반환해 이 시나리오 자체가 성립하지 않는다).
        Facility facility = facilityRepository.save(Facility.builder()
                .companyId(company.getId())
                .name("데드락테스트시설")
                .type("BUILDING")
                .inspectionCycleMonths(6)
                .build());

        this.ownerId = owner.getId();
        this.companyId = company.getId();
        this.facilityId = facility.getId();
        this.ownerMembershipId = membership.getId();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from reports where inspection_id in "
                + "(select id from inspections where facility_id = ?)", facilityId);
        jdbcTemplate.update("delete from inspections where facility_id = ?", facilityId);
        storageKeyByReportId.keySet().forEach(reportPdfStorage::deleteAll);
        storageKeyByReportId.clear();
        facilityRepository.deleteById(facilityId);
        companyMembershipRepository.deleteById(ownerMembershipId);

        // circular FK(companies.owner_user_id ↔ users.company_id) — company_id 를 먼저 끊어야 순서대로 지운다.
        User owner = userRepository.findById(ownerId).orElseThrow();
        owner.assignToCompany(null);
        userRepository.save(owner);
        companyRepository.deleteById(companyId);
        userRepository.deleteById(ownerId);
    }

    @Test
    void 소급회차생성과보고서확정이_같은시설물에동시에와도_데드락없이모두성공한다() throws Exception {
        // 확정 대상 회차를 ROUNDS 개 미리 만든다(각각 ANALYZED + DRAFT 보고서 1건).
        // 날짜를 넉넉히 과거로 두어, 뒤에서 만드는 소급 회차가 이들 "앞"에 끼어들어 시프트를 유발하게 한다.
        List<Long> reportIds = new ArrayList<>();
        for (int i = 0; i < ROUNDS; i++) {
            Long inspectionId = inspectionService.createInspection(
                    new InspectionCreateRequest(facilityId, LocalDate.now().minusDays(100L - i), ownerId),
                    companyId, ownerId).id();
            markAnalyzed(inspectionId);
            reportIds.add(saveDraftReport(inspectionId));
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < ROUNDS; i++) {
            Long reportId = reportIds.get(i);
            // 매 라운드 서로 다른 소급 날짜 — 항상 기존 회차들 사이/앞에 꽂혀 시프트가 실제로 일어난다.
            LocalDate backdated = LocalDate.now().minusDays(200L + i);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Future<String> reorder = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    inspectionService.createInspection(
                            new InspectionCreateRequest(facilityId, backdated, ownerId), companyId, ownerId);
                    return null;
                } catch (RuntimeException e) {
                    return "createInspection: " + e.getClass().getName() + " - " + e.getMessage();
                }
            });
            Future<String> finalize = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    reportService.finalizeReport(reportId,
                            "/api/reports/%d/pdf/%s".formatted(reportId, storageKeyByReportId.get(reportId)),
                            companyId, ownerId);
                    return null;
                } catch (RuntimeException e) {
                    return "finalizeReport: " + e.getClass().getName() + " - " + e.getMessage();
                }
            });

            ready.await();
            start.countDown();
            for (Future<String> future : List.of(reorder, finalize)) {
                String failure = future.get(60, TimeUnit.SECONDS);
                if (failure != null) {
                    failures.add(failure);
                }
            }
        }
        executor.shutdown();

        // 락 순서가 역전돼 있으면 한쪽이 40P01(deadlock detected)로 abort된다 —
        // Spring 은 이를 CannotAcquireLockException 계열로 감싸 던진다.
        assertThat(failures)
                .as("소급 회차 생성과 보고서 확정은 시설물 행 잠금으로 직렬화되어야 하며 데드락으로 실패하면 안 된다")
                .isEmpty();

        // 직렬화가 정합까지 지켰는지 확인 — 회차 번호는 여전히 유일하고 1..N 로 조밀해야 한다.
        List<Integer> roundNos = inspectionRepository.findByFacilityIdIn(List.of(facilityId)).stream()
                .map(inspection -> inspection.getRoundNo())
                .sorted()
                .toList();
        assertThat(roundNos).doesNotHaveDuplicates();
        assertThat(roundNos.get(0)).isEqualTo(1);
        assertThat(roundNos.get(roundNos.size() - 1)).isEqualTo(roundNos.size());
        assertThat(reportRepository.findById(reportIds.get(0)).orElseThrow().getStatus())
                .isEqualTo(ReportStatus.FINALIZED);
    }

    private void markAnalyzed(Long inspectionId) {
        // 상태 머신을 우회한 픽스처 세팅 — 이 테스트의 관심사는 상태 전이 규칙이 아니라 락 순서다.
        jdbcTemplate.update(
                "update inspections set status = 'ANALYZED'::inspection_status_type where id = ?", inspectionId);
    }

    private Long saveDraftReport(Long inspectionId) {
        int roundNo = inspectionRepository.findById(inspectionId).orElseThrow().getRoundNo();
        Long reportId = reportRepository.saveAndFlush(
                Report.draft(inspectionId, roundNo, 1, FINALIZABLE_CONTENT_JSON, ownerId)).getId();
        // finalize 는 pdfUrl 이 가리키는 파일을 실제로 읽어 존재를 확인한다(requireOwnPdfUrl → load).
        storageKeyByReportId.put(reportId, reportPdfStorage.store(reportId, pdfFile()));
        return reportId;
    }

    private static MockMultipartFile pdfFile() {
        // content-type + PDF 매직넘버(%PDF-) 둘 다 통과해야 저장된다(LocalReportPdfStorage.store 참고).
        return new MockMultipartFile(
                "file", "r.pdf", "application/pdf", "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.UTF_8));
    }
}
