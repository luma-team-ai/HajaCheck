package com.hajacheck.core.inspection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.analysis.support.InspectionAnalysisNotificationPayload;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.dto.InspectionCreateRequest;
import com.hajacheck.core.inspection.dto.InspectionResponse;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.entity.ReportStatus;
import com.hajacheck.core.report.repository.ReportRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.notification.dto.NotificationResponse;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import com.hajacheck.notification.service.NotificationService;
import com.hajacheck.support.PostgresTestSupport;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 점검일 소급 회차 생성 시 회차 번호 재정렬(#1702)을 실 PostgreSQL(Testcontainers)로 고정한다.
 *
 * <p>단위 테스트(InspectionServiceTest)는 삽입 위치 계산과 호출 순서만 검증할 수 있고, 이 기능의 핵심
 * 위험 — <b>non-deferrable {@code unique(facility_id, round_no)} 아래에서 시프트가 실제로 통과하는가</b> —
 * 는 실 DB에서만 드러난다. 단일 {@code round_no = round_no + 1} UPDATE로 되돌리면 여기서 즉시 깨진다.
 *
 * <p>클래스 레벨 {@code @Transactional}로 테스트 종료 시 롤백한다. 회차 재정렬 경로는 벌크 UPDATE
 * ({@code clearAutomatically = true})를 쓰므로, 각 단계 뒤에는 반드시 리포지토리로 다시 읽는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InspectionRoundReorderIntegrationTest extends PostgresTestSupport {

    @Autowired
    private InspectionService inspectionService;
    @Autowired
    private NotificationService notificationService;
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
    private NotificationRepository notificationRepository;
    @Autowired
    private EntityManager entityManager;

    private Long ownerId;
    private Long companyId;
    private Long facilityId;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(User.builder()
                .email("reorder-owner-" + System.nanoTime() + "@haja.com")
                .name("재정렬테스트소유자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build());

        // business_registration_number 는 varchar(20) — 접두사 짧게 + nanoTime 뒷자리로 유니크성 확보.
        String brn = "brn-" + (System.nanoTime() % 10_000_000_000L);
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)재정렬테스트", brn,
                "김대표", "서울시 강남구", null, "http://files/brn.png", "{}"));
        company.markBusinessVerified();
        company.approve(owner.getId());
        company = companyRepository.save(company);
        owner.assignToCompany(company.getId());
        userRepository.save(owner);
        companyMembershipRepository.save(CompanyMembership.approvedOwner(company.getId(), owner.getId()));

        Facility facility = facilityRepository.save(Facility.builder()
                .companyId(company.getId()).name("재정렬테스트시설").type("BUILDING").build());

        this.ownerId = owner.getId();
        this.companyId = company.getId();
        this.facilityId = facility.getId();
        flushAndClear();
    }

    @Test
    void 최신회차보다이전날짜_거부되지않고_회차가점검일오름차순으로재배열된다() {
        create(LocalDate.of(2026, 7, 10));
        create(LocalDate.of(2026, 7, 25));
        create(LocalDate.of(2026, 7, 28));

        // #1291이 막던 바로 그 입력 — 19일 등록 후 빠뜨린 18일 점검을 넣는 정상 업무 흐름.
        InspectionResponse backdated = create(LocalDate.of(2026, 7, 20));

        assertThat(backdated.roundNo()).isEqualTo(2);
        assertThat(roundNoByDate()).containsExactly(
                entry(LocalDate.of(2026, 7, 10), 1),
                entry(LocalDate.of(2026, 7, 20), 2),
                entry(LocalDate.of(2026, 7, 25), 3),
                entry(LocalDate.of(2026, 7, 28), 4));
    }

    @Test
    void 맨앞으로소급입력해도_뒤회차전체가한칸씩밀린다() {
        create(LocalDate.of(2026, 7, 10));
        create(LocalDate.of(2026, 7, 25));

        InspectionResponse earliest = create(LocalDate.of(2026, 7, 1));

        assertThat(earliest.roundNo()).isEqualTo(1);
        assertThat(roundNoByDate()).containsExactly(
                entry(LocalDate.of(2026, 7, 1), 1),
                entry(LocalDate.of(2026, 7, 10), 2),
                entry(LocalDate.of(2026, 7, 25), 3));
    }

    @Test
    void 같은날짜생성_기존동일날짜회차뒤에붙는다() {
        create(LocalDate.of(2026, 7, 10));
        InspectionResponse sameDay = create(LocalDate.of(2026, 7, 10));

        assertThat(sameDay.roundNo()).isEqualTo(2);
    }

    @Test
    void 미래날짜는_여전히거부된다() {
        assertThatThrownBy(() -> create(LocalDate.now().plusDays(1)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSPECTION_DATE_INVALID));
    }

    /**
     * {@code DefectService#confirmPreviousDefect}는 "이전 회차"를 {@code previous.roundNo <
     * current.roundNo}로만 판정한다(#1702 회귀선). 시프트는 기존 회차들을 통째로 한 칸씩 밀 뿐이라
     * <b>상대 순서를 보존</b>해야 하며, 그래야 이 판정이 재정렬 후에도 그대로 성립한다.
     */
    @Test
    void 시프트후에도_기존회차간상대순서가보존된다() {
        InspectionResponse first = create(LocalDate.of(2026, 7, 10));
        InspectionResponse second = create(LocalDate.of(2026, 7, 25));
        InspectionResponse third = create(LocalDate.of(2026, 7, 28));

        create(LocalDate.of(2026, 7, 20));

        int firstAfter = roundNoOf(first.id());
        int secondAfter = roundNoOf(second.id());
        int thirdAfter = roundNoOf(third.id());
        assertThat(firstAfter).isLessThan(secondAfter);
        assertThat(secondAfter).isLessThan(thirdAfter);
        // 밀린 폭도 동일해야 한다 — 삽입 위치 이상인 회차는 정확히 +1 씩만 움직인다.
        assertThat(secondAfter).isEqualTo(second.roundNo() + 1);
        assertThat(thirdAfter).isEqualTo(third.roundNo() + 1);
    }

    @Test
    void FINALIZED보고서의회차는동결되고_DRAFT는재정렬을따라간다() {
        create(LocalDate.of(2026, 7, 10));
        InspectionResponse target = create(LocalDate.of(2026, 7, 25));
        Long finalizedId = saveFinalizedReport(target.id(), target.roundNo(), 1);
        Long draftId = saveDraftReport(target.id(), target.roundNo(), 2);

        create(LocalDate.of(2026, 7, 20));

        assertThat(roundNoOf(target.id())).isEqualTo(target.roundNo() + 1);
        // 확정 보고서는 발급 시점에 동결 — 이미 인쇄·제출된 PDF 표지의 "제N회차"와 영구 일치해야 한다.
        assertThat(reportRoundNoOf(finalizedId)).isEqualTo(target.roundNo());
        // 초안은 아직 발급 전 살아있는 문서라 현재 회차를 따라간다.
        assertThat(reportRoundNoOf(draftId)).isEqualTo(target.roundNo() + 1);
    }

    /**
     * #1706 — 알림 payload에 굳혀 저장된 "{roundNo}회차"는 재정렬 뒤 stale해진다. 조회 시점에 현재
     * 회차로 다시 계산되는지 재정렬과 묶어 고정한다.
     */
    @Test
    void 회차시프트후_기존알림조회시_현재회차가표시된다() {
        create(LocalDate.of(2026, 7, 10));
        InspectionResponse target = create(LocalDate.of(2026, 7, 25));
        notificationRepository.save(Notification.create(ownerId, NotificationType.ANALYSIS_DONE,
                InspectionAnalysisNotificationPayload.serialize(target.id(), target.roundNo())));

        create(LocalDate.of(2026, 7, 20));
        flushAndClear();

        NotificationResponse notification = analysisNotification();
        assertThat(roundNoOf(target.id())).isEqualTo(target.roundNo() + 1);
        assertThat(notification.payload().get("description").asText())
                .isEqualTo((target.roundNo() + 1) + "회차");
    }

    @Test
    void 대상점검이사라진알림은_저장된회차표기를그대로유지한다() {
        // 존재하지 않는 점검 id — 삭제된 회차(데모 리셋 등)를 가리키는 알림을 모사한다.
        notificationRepository.save(Notification.create(ownerId, NotificationType.ANALYSIS_DONE,
                InspectionAnalysisNotificationPayload.serialize(-1L, 7)));
        flushAndClear();

        assertThat(analysisNotification().payload().get("description").asText()).isEqualTo("7회차");
    }

    @Test
    void 회차가없는알림유형의payload는_건드리지않는다() {
        notificationRepository.save(Notification.create(ownerId, NotificationType.INSPECTION_DUE,
                "{\"facilityId\":1,\"description\":\"점검 예정일이 다가왔습니다\"}"));
        flushAndClear();

        NotificationResponse response = notificationService.getNotifications(ownerId).stream()
                .filter(item -> NotificationType.INSPECTION_DUE.name().equals(item.type()))
                .findFirst()
                .orElseThrow();
        assertThat(response.payload().get("description").asText()).isEqualTo("점검 예정일이 다가왔습니다");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private InspectionResponse create(LocalDate inspectionDate) {
        InspectionResponse response = inspectionService.createInspection(
                new InspectionCreateRequest(facilityId, inspectionDate, ownerId), companyId, ownerId);
        flushAndClear();
        return response;
    }

    private NotificationResponse analysisNotification() {
        return notificationService.getNotifications(ownerId).stream()
                .filter(item -> NotificationType.ANALYSIS_DONE.name().equals(item.type()))
                .findFirst()
                .orElseThrow();
    }

    private Long saveDraftReport(Long inspectionId, int roundNo, int version) {
        return reportRepository.saveAndFlush(
                Report.draft(inspectionId, roundNo, version, "{\"summary\":\"초안\"}", ownerId)).getId();
    }

    private Long saveFinalizedReport(Long inspectionId, int roundNo, int version) {
        Report report = Report.draft(inspectionId, roundNo, version, "{\"summary\":\"확정\"}", ownerId);
        report.recordStructuralGroundingRecheck(true, null, ownerId);
        report.finalizeReport("https://files.example.com/reports/reorder.pdf", ownerId);
        return reportRepository.saveAndFlush(report).getId();
    }

    private int reportRoundNoOf(Long reportId) {
        flushAndClear();
        return reportRepository.findById(reportId).orElseThrow().getRoundNo();
    }

    private int roundNoOf(Long inspectionId) {
        flushAndClear();
        return inspectionRepository.findById(inspectionId).orElseThrow().getRoundNo();
    }

    private List<java.util.Map.Entry<LocalDate, Integer>> roundNoByDate() {
        flushAndClear();
        return inspectionRepository.findByFacilityIdIn(List.of(facilityId)).stream()
                .sorted(Comparator.comparing(Inspection::getRoundNo))
                .map(inspection -> entry(inspection.getInspectionDate(), inspection.getRoundNo()))
                .toList();
    }

    private static java.util.Map.Entry<LocalDate, Integer> entry(LocalDate date, int roundNo) {
        return java.util.Map.entry(date, roundNo);
    }

    /** 벌크 UPDATE(clearAutomatically)와 1차 캐시가 섞이지 않도록 매 단계 경계에서 비운다. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void FINALIZED보고서목록의회차도_스냅샷을따른다() {
        create(LocalDate.of(2026, 7, 10));
        InspectionResponse target = create(LocalDate.of(2026, 7, 25));
        saveFinalizedReport(target.id(), target.roundNo(), 1);

        create(LocalDate.of(2026, 7, 20));
        flushAndClear();

        // 회차 필터도 스냅샷(reports.round_no) 기준 — 재정렬 후에도 발급 당시 번호로 찾힌다.
        assertThat(reportRepository.findCompanyPage(companyId, List.of(ReportStatus.FINALIZED), -1L,
                        target.roundNo(), "", java.time.LocalDateTime.of(2000, 1, 1, 0, 0),
                        org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(1);
    }
}
