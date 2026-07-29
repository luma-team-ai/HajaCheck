package com.hajacheck.core.defect.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectActionLog;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

// 실 PG DDL(defect_action_logs, V32)과의 대조를 위해 Testcontainers PostgreSQL 사용(#1193/HAJA-569).
// users → facilities → inspections → defects/media 순으로 FK를 충족하며 시드한다(DefectRepositoryTest와 동일 패턴).
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class DefectActionLogRepositoryTest extends PostgresTestSupport {

    @Autowired
    private DefectActionLogRepository defectActionLogRepository;

    @Autowired
    private TestEntityManager em;

    private Long seedOwner(String email) {
        User owner = User.builder()
                .email(email)
                .name("소유자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build();
        em.persist(owner);
        em.flush();

        Company company = Company.createPendingReview(
                owner.getId(), "테스트회사-" + owner.getId(), "REG-" + owner.getId(), "대표자",
                "서울시 강남구", null, "https://files.example.com/registration.png", "{}");
        em.persist(company);
        em.flush();
        company.markBusinessVerified();
        company.approve(owner.getId());
        em.flush();

        em.persist(CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        owner.assignToCompany(company.getId());
        em.flush();

        return owner.getId();
    }

    private Long seedFacility(Long ownerId) {
        Long companyId = em.find(User.class, ownerId).getCompanyId();
        Facility facility = Facility.builder().companyId(companyId).name("테스트빌딩").type("BUILDING").build();
        em.persist(facility);
        em.flush();
        return facility.getId();
    }

    private Long seedInspection(Long facilityId, Long createdBy) {
        Inspection inspection = Inspection.builder()
                .facilityId(facilityId)
                .createdBy(createdBy)
                .assignedInspectorId(createdBy)
                .roundNo(1)
                .inspectionDate(LocalDate.of(2026, 7, 1))
                .status(InspectionStatus.REVIEWED)
                .build();
        em.persist(inspection);
        em.flush();
        return inspection.getId();
    }

    private Long seedDefect(Long inspectionId, DefectStatus status) {
        Defect defect = Defect.builder()
                .inspectionId(inspectionId)
                .type(DefectType.CRACK)
                .confidence(0.9)
                .grade(DefectGrade.C)
                .status(status)
                .reviewed(true)
                .deleted(false)
                .build();
        em.persist(defect);
        em.flush();
        return defect.getId();
    }

    private Long seedMedia(Long inspectionId) {
        Media media = Media.builder()
                .inspectionId(inspectionId)
                .fileType(MediaFileType.IMAGE)
                .originalUrl("s3://test-bucket/original.jpg")
                .thumbnailUrl("s3://test-bucket/thumb.jpg")
                .mimeSignatureVerified(true)
                .mimeType("image/jpeg")
                .build();
        em.persist(media);
        em.flush();
        return media.getId();
    }

    @Test
    void save_저장후_createdAt과id채워짐() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId);
        Long inspectionId = seedInspection(facilityId, ownerId);
        Long defectId = seedDefect(inspectionId, DefectStatus.IN_PROGRESS);
        Long mediaId = seedMedia(inspectionId);

        DefectActionLog saved = defectActionLogRepository.save(DefectActionLog.record(
                defectId, mediaId, DefectStatus.IN_PROGRESS, "1차 보수",
                LocalDate.of(2026, 7, 28), ownerId));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getPhase()).isEqualTo(DefectStatus.IN_PROGRESS);
    }

    @Test
    void findByDefectIdAndPhaseOrderByCreatedAtDesc_같은phase만_최신순반환() {
        Long ownerId = seedOwner("owner-b@haja.com");
        Long facilityId = seedFacility(ownerId);
        Long inspectionId = seedInspection(facilityId, ownerId);
        Long defectId = seedDefect(inspectionId, DefectStatus.RESOLVED);
        Long mediaId = seedMedia(inspectionId);

        defectActionLogRepository.save(DefectActionLog.record(
                defectId, mediaId, DefectStatus.IN_PROGRESS, "1차 보수",
                LocalDate.of(2026, 7, 28), ownerId));
        em.flush();
        defectActionLogRepository.save(DefectActionLog.record(
                defectId, mediaId, DefectStatus.IN_PROGRESS, "2차 보수",
                LocalDate.of(2026, 7, 29), ownerId));
        em.flush();
        // 다른 phase(RESOLVED) 이력은 IN_PROGRESS 조회 결과에 섞이면 안 된다.
        defectActionLogRepository.save(DefectActionLog.record(
                defectId, mediaId, DefectStatus.RESOLVED, "보수 완료",
                LocalDate.of(2026, 7, 30), ownerId));
        em.flush();

        List<DefectActionLog> result =
                defectActionLogRepository.findByDefectIdAndPhaseOrderByCreatedAtDesc(defectId, DefectStatus.IN_PROGRESS);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getActionContent()).isEqualTo("2차 보수");
        assertThat(result.get(1).getActionContent()).isEqualTo("1차 보수");
    }

    @Test
    void findByDefectIdAndPhaseOrderByCreatedAtDesc_이력없으면빈리스트() {
        Long ownerId = seedOwner("owner-c@haja.com");
        Long facilityId = seedFacility(ownerId);
        Long inspectionId = seedInspection(facilityId, ownerId);
        Long defectId = seedDefect(inspectionId, DefectStatus.DETECTED);

        List<DefectActionLog> result =
                defectActionLogRepository.findByDefectIdAndPhaseOrderByCreatedAtDesc(defectId, DefectStatus.IN_PROGRESS);

        assertThat(result).isEmpty();
    }
}
