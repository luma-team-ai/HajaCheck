package com.hajacheck.core.media.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.User;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import com.hajacheck.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
        User owner = User.createCompanyOwner("owner-a@haja.com", "소유자", "$2a$10$testtesttesttesttesttes");
        em.persist(owner);
        em.flush();

        Facility facility = Facility.builder().ownerId(owner.getId()).name("테스트빌딩").type("BUILDING").build();
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
}
