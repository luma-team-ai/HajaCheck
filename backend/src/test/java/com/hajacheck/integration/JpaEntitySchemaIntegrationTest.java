package com.hajacheck.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.ConsentPolicyType;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserConsent;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.core.ai.dto.ReportResponse;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectRevision;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.entity.InspectionType;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import com.hajacheck.core.rag.entity.ChatMessageCitation;
import com.hajacheck.core.rag.entity.RagDocument;
import com.hajacheck.core.rag.entity.RagDocumentSourceType;
import com.hajacheck.core.rag.entity.RagEmbeddingStatus;
import com.hajacheck.core.rag.entity.RagTargetCollection;
import com.hajacheck.core.report.entity.GroundingCheckResult;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.service.GroundingCheckResultFactory;
import com.hajacheck.core.report.service.GroundingReportContentSerializer;
import com.hajacheck.counsel.entity.BotScenario;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.entity.ChatSession;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class JpaEntitySchemaIntegrationTest extends PostgresTestSupport {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void companyMembership_저장조회() {
        User owner = seedUser("membership-owner@haja.com");
        User member = seedUser("membership-member@haja.com");
        Company company = Company.createPendingReview(
                owner.getId(), "하자체크", "123-45-67890", "대표자", "서울시",
                null, "https://files.example/business.pdf", "{\"source\":\"MANUAL_INPUT\"}");
        em.persistAndFlush(company);

        CompanyMembership membership = CompanyMembership.invite(
                company.getId(), member.getId(), owner.getId(), Instant.now().plusSeconds(3600));
        membership.approve();
        em.persistAndFlush(membership);
        UserConsent consent = UserConsent.of(
                member.getId(), ConsentPolicyType.TERMS_OF_SERVICE, "v1.0");
        em.persistAndFlush(consent);
        Long membershipId = membership.getId();
        Long consentId = consent.getId();
        em.clear();

        CompanyMembership found = em.find(CompanyMembership.class, membershipId);

        assertThat(found.getCompanyId()).isEqualTo(company.getId());
        assertThat(found.getUserId()).isEqualTo(member.getId());
        assertThat(found.getCompany().getOwnerUser().getId()).isEqualTo(owner.getId());
        assertThat(found.getUser().getEmail()).isEqualTo("membership-member@haja.com");
        assertThat(found.getInviter().getId()).isEqualTo(owner.getId());
        assertThat(found.isEffectiveAt(Instant.now())).isTrue();
        assertThat(em.find(UserConsent.class, consentId).getUser().getId()).isEqualTo(member.getId());
    }

    @Test
    void inspectionMediaDefectRevisionReport_저장조회() {
        User owner = seedInspectorOwner("inspection-owner@haja.com");
        Inspection inspection = seedInspection(owner, "통합검증 시설");
        assertThat(inspection.getType()).isEqualTo(InspectionType.REGULAR);

        Media media = Media.builder()
                .inspectionId(inspection.getId())
                .fileType(MediaFileType.IMAGE)
                .originalUrl("https://files.example/image.jpg")
                .capturedAt(LocalDateTime.now())
                .mimeSignatureVerified(true)
                .mimeType("image/jpeg")
                .build();
        em.persist(media);

        Defect defect = Defect.builder()
                .inspectionId(inspection.getId())
                .type(DefectType.CRACK)
                .confidence(0.97)
                .status(DefectStatus.DETECTED)
                .build();
        em.persist(defect);
        em.flush();

        DefectRevision revision = DefectRevision.record(
                defect.getId(), owner.getId(), "status", "DETECTED", "CONFIRMED", "현장 검토");
        em.persist(revision);

        Report report = Report.draft(
                inspection.getId(), 1, "{\"summary\":\"균열 발견\"}", owner.getId());
        var groundingContext = report.captureGroundingRequestContext();
        var aiReport = new ReportResponse(
                null,
                null,
                null,
                null,
                true,
                groundingContext.groundingRequestId(),
                groundingContext.inspectionId(),
                groundingContext.reportVersion(),
                "d6efff2a647c31d336f34cf55d1475873932bf6d826cbe236f2fd01c6017dcaf");
        report.updateContent(GroundingReportContentSerializer.serialize(aiReport), owner.getId());
        report.recordGroundingResult(
                GroundingCheckResultFactory.fromAiReport(
                        groundingContext,
                        aiReport,
                        "[]"),
                owner.getId());
        em.persist(report);
        em.flush();

        Long mediaId = media.getId();
        Long revisionId = revision.getId();
        Long reportId = report.getId();
        em.clear();

        Media foundMedia = em.find(Media.class, mediaId);
        assertThat(foundMedia.getMimeType()).isEqualTo("image/jpeg");
        assertThat(foundMedia.isMimeSignatureVerified()).isTrue();
        assertThat(foundMedia.getInspection().getFacility().getName()).isEqualTo("통합검증 시설");
        DefectRevision foundRevision = em.find(DefectRevision.class, revisionId);
        Report foundReport = em.find(Report.class, reportId);
        assertThat(foundRevision.getReason()).isEqualTo("현장 검토");
        assertThat(foundRevision.getDefect().getInspection().getId()).isEqualTo(inspection.getId());
        assertThat(foundReport.getContentJson()).containsIgnoringWhitespaces("\"grounding_ok\":true");
        assertThat(foundReport.getGroundingWarnings()).isEqualTo("[]");
        assertThat(foundReport.getInspection().getId()).isEqualTo(inspection.getId());
    }

    @Test
    void chatRagCitation_관계와외부Chunk참조저장조회() {
        User user = seedUser("chat-user@haja.com");
        ChatSession scenarioSession = ChatSession.start(user.getId(), ChatSessionType.SCENARIO_BOT);
        em.persist(scenarioSession);
        ChatSession counselSession = ChatSession.start(user.getId(), ChatSessionType.COUNSEL);
        em.persist(counselSession);

        BotScenario root = BotScenario.create(null, "시설", "시설 점검", "선택하세요", false, 0);
        em.persist(root);
        BotScenario child = BotScenario.create(root.getId(), "시설", "균열", "균열 안내", false, 1);
        em.persist(child);

        ChatMessage message = ChatMessage.create(
                scenarioSession.getId(), ChatSenderType.BOT, "관련 근거입니다", child.getId());
        em.persist(message);

        CounselTicket ticket = CounselTicket.request(user.getId(), 1);
        ticket.assign(user.getId(), counselSession);
        em.persist(ticket);

        RagDocument document = RagDocument.upload(
                "시설물 안전 지침", RagDocumentSourceType.GUIDELINE,
                RagTargetCollection.REGULATIONS, null, "국토교통부", null,
                null, "https://files.example/guideline.pdf");
        document.startEmbedding();
        document.completeEmbedding(2);
        em.persist(document);
        em.flush();

        ChatMessageCitation first = ChatMessageCitation.create(
                message.getId(), document.getId(), "chunk-1", "제1조", "첫 번째 근거");
        ChatMessageCitation second = ChatMessageCitation.create(
                message.getId(), document.getId(), "chunk-2", "제2조", "두 번째 근거");
        em.persist(first);
        em.persist(second);
        em.flush();

        Long messageId = message.getId();
        Long documentId = document.getId();
        Long firstId = first.getId();
        Long secondId = second.getId();
        em.clear();

        assertThat(em.find(ChatMessage.class, messageId).getScenario().getId()).isEqualTo(child.getId());
        assertThat(em.find(RagDocument.class, documentId).getEmbeddingStatus())
                .isEqualTo(RagEmbeddingStatus.DONE);
        assertThat(List.of(
                em.find(ChatMessageCitation.class, firstId).getChunkRef(),
                em.find(ChatMessageCitation.class, secondId).getChunkRef()))
                .containsExactly("chunk-1", "chunk-2");
        assertThat(em.find(ChatMessageCitation.class, firstId).getDocument().getId())
                .isEqualTo(documentId);
    }

    @Test
    void counselTicket_동일상담세션중복배정_거부() {
        User user = seedUser("counsel-unique-user@haja.com");
        ChatSession session = ChatSession.start(user.getId(), ChatSessionType.COUNSEL);
        em.persistAndFlush(session);
        CounselTicket first = CounselTicket.request(user.getId(), 1);
        first.assign(user.getId(), session);
        em.persistAndFlush(first);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into counsel_tickets (user_id, counselor_id, session_id, status, queue_position)
                values (?, ?, ?, 'IN_PROGRESS'::counsel_ticket_status_type, null)
                """, user.getId(), user.getId(), session.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void notification_jsonb와미확인조회() {
        User user = seedUser("notification-user@haja.com");
        Notification unread = Notification.create(
                user.getId(), NotificationType.ANALYSIS_DONE, "{\"inspectionId\":10}");
        Notification read = Notification.create(
                user.getId(), NotificationType.REVIEW_PENDING, "{\"inspectionId\":20}");
        read.markAsRead();
        notificationRepository.saveAllAndFlush(List.of(unread, read));
        em.clear();

        List<Notification> result =
                notificationRepository.findAllByUserIdAndReadFalseOrderByCreatedAtDesc(
                        user.getId(), PageRequest.of(0, 20));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPayloadJson()).isEqualToIgnoringWhitespace("{\"inspectionId\":10}");
    }

    private User seedUser(String email) {
        User user = User.createCompanyOwner(email, "사용자", "<password-hash-placeholder>");
        em.persistAndFlush(user);
        return user;
    }

    // HAJA-25 배정 검증 트리거(trg_inspections_check_assigned_inspector_company)는
    // assigned_inspector_id가 승인+검증된 회사에 속한 INSPECTOR/ADMIN 역할이면서 유효한
    // APPROVED 멤버십을 가질 것을 요구한다. seedInspection이 owner를 그대로 담당자로도
    // 재사용하므로, 이 픽스처는 역할을 INSPECTOR로 두고 승인된 회사·멤버십을 함께 시드한다.
    private User seedInspectorOwner(String email) {
        User owner = User.builder()
                .email(email)
                .name("사용자")
                .role(Role.INSPECTOR)
                .passwordHash("<password-hash-placeholder>")
                .status(UserStatus.ACTIVE)
                .build();
        em.persistAndFlush(owner);

        Company company = Company.createPendingReview(
                owner.getId(), "통합검증회사-" + owner.getId(), "REG-" + owner.getId(), "대표자",
                "서울시", null, "https://files.example/business.pdf", "{\"source\":\"MANUAL_INPUT\"}");
        em.persistAndFlush(company);
        company.markBusinessVerified();
        company.approve(owner.getId());
        em.flush();

        em.persistAndFlush(CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        owner.assignToCompany(company.getId());
        em.persistAndFlush(owner);

        return owner;
    }

    private Inspection seedInspection(User owner, String facilityName) {
        Facility facility = Facility.builder()
                .ownerId(owner.getId())
                .name(facilityName)
                .type("BUILDING")
                .build();
        em.persistAndFlush(facility);

        Inspection inspection = Inspection.builder()
                .facilityId(facility.getId())
                .createdBy(owner.getId())
                .assignedInspectorId(owner.getId())
                .roundNo(1)
                .inspectionDate(LocalDate.of(2026, 7, 16))
                .status(InspectionStatus.CREATED)
                .build();
        em.persistAndFlush(inspection);
        return inspection;
    }
}
