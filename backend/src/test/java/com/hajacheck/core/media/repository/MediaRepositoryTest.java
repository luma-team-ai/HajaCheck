package com.hajacheck.core.media.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import com.hajacheck.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

// 실 PG DDL(media) 대조를 위해 Testcontainers PostgreSQL 사용(dev-05-03).
// users → facilities → inspections → media 순으로 FK 를 충족하며 시드한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class MediaRepositoryTest extends PostgresTestSupport {

    @Autowired
    private MediaRepository mediaRepository;

    @Autowired
    private TestEntityManager em;

    private Long seedInspection() {
        // HAJA-25 배정 검증 트리거: created_by·assigned_inspector 는 승인+검증된 회사의 유효한 APPROVED
        // 멤버여야 하고 담당자는 INSPECTOR/ADMIN 역할이어야 한다. owner 를 두 역할로 함께 재사용하므로
        // INSPECTOR 역할 + 승인/검증 회사 + approvedOwner 멤버십 + company_id 를 함께 시드한다.
        User owner = User.builder()
                .email("owner-a@haja.com")
                .name("소유자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build();
        em.persist(owner);
        em.flush();

        Company company = Company.createPendingReview(
                owner.getId(), "미디어테스트회사", "REG-" + owner.getId(), "대표자",
                "서울시", null, "https://files.example/business.pdf", "{}");
        em.persist(company);
        em.flush();
        company.markBusinessVerified();
        company.approve(owner.getId());
        em.flush();

        em.persist(CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        owner.assignToCompany(company.getId());
        em.persist(owner);
        em.flush();

        Facility facility = Facility.builder().companyId(company.getId()).name("테스트빌딩").type("BUILDING").build();
        em.persist(facility);
        em.flush();

        Inspection inspection = Inspection.builder()
                .facilityId(facility.getId())
                .createdBy(owner.getId())
                .assignedInspectorId(owner.getId())
                .roundNo(1)
                .inspectionDate(LocalDate.of(2026, 7, 1))
                .status(InspectionStatus.CREATED)
                .build();
        em.persist(inspection);
        em.flush();

        return inspection.getId();
    }

    @Test
    void save_저장후_createdAt과id채워짐_필수컬럼만() {
        Long inspectionId = seedInspection();
        Media media = Media.builder()
                .inspectionId(inspectionId)
                .fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/a.png")
                .mimeSignatureVerified(true)
                .build();

        Media saved = mediaRepository.save(media);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getFileType()).isEqualTo(MediaFileType.IMAGE);
        assertThat(saved.isMimeSignatureVerified()).isTrue();
        assertThat(saved.getThumbnailUrl()).isNull();
        assertThat(saved.getGpsLat()).isNull();
    }

    @Test
    void save_전체컬럼채움_정상저장() {
        Long inspectionId = seedInspection();
        Media media = Media.builder()
                .inspectionId(inspectionId)
                .fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/a.png")
                .thumbnailUrl("inspection-media-thumb/a.jpg")
                .capturedAt(LocalDateTime.of(2026, 7, 1, 12, 0))
                .gpsLat(new BigDecimal("37.500000"))
                .gpsLng(new BigDecimal("127.000000"))
                .mimeSignatureVerified(true)
                .mimeType("image/png")
                .build();

        Media saved = mediaRepository.save(media);

        assertThat(saved.getThumbnailUrl()).isEqualTo("inspection-media-thumb/a.jpg");
        assertThat(saved.getCapturedAt()).isEqualTo(LocalDateTime.of(2026, 7, 1, 12, 0));
        assertThat(saved.getGpsLat()).isEqualByComparingTo("37.500000");
        assertThat(saved.getGpsLng()).isEqualByComparingTo("127.000000");
        assertThat(saved.getMimeType()).isEqualTo("image/png");
    }

    /**
     * captured_at 은 DDL상 timestamptz 인데 엔티티 필드는 naive LocalDateTime(카메라 현지시각)이다
     * (리뷰 P2). CapturedAtConverter 가 서버 TZ와 무관하게 고정 존(Asia/Seoul)으로 변환하는지
     * 실제 PG 라운드트립으로 검증한다 — 저장 시점과 조회 시점의 JVM 기본 TZ 를 서로 다르게 바꿔도
     * 원문 벽시계 값이 그대로 복원되어야 한다.
     */
    @Test
    void save_저장과조회사이TZ변경돼도_capturedAt은원문벽시계값유지() {
        TimeZone originalDefault = TimeZone.getDefault();
        try {
            Long inspectionId = seedInspection();
            LocalDateTime raw = LocalDateTime.of(2024, 3, 15, 14, 30, 0);

            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Media media = Media.builder()
                    .inspectionId(inspectionId)
                    .fileType(MediaFileType.IMAGE)
                    .originalUrl("inspection-media/a.png")
                    .capturedAt(raw)
                    .mimeSignatureVerified(true)
                    .build();
            Media saved = mediaRepository.save(media);
            em.flush();
            em.clear();

            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            Media reloaded = mediaRepository.findById(saved.getId()).orElseThrow();

            assertThat(reloaded.getCapturedAt()).isEqualTo(raw);
        } finally {
            TimeZone.setDefault(originalDefault);
        }
    }
}
