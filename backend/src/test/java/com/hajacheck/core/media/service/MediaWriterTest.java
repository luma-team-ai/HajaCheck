package com.hajacheck.core.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.entity.InspectionType;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import com.hajacheck.core.media.entity.MediaPurpose;
import com.hajacheck.core.media.repository.MediaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * MediaWriter.applyPerformedAt(V43, #1667) 단위 검증 — 회차의 INSPECTION_SOURCE 미디어 저장 직후
 * 자동으로 Inspection.performedAt을 세팅/갱신하는 로직. 실제 DB 대신 mediaRepository.saveAll을
 * 그대로 통과시키고(전달값 = 반환값), inspectionRepository.findAllById로 조회되는 Inspection의
 * applyPerformedAt 결과를 검증한다(MediaServiceTest가 mediaWriter를 목킹하는 것과 대칭).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaWriterTest {

    // SchedulingConfig.clock()과 동일하게 Asia/Seoul(KST) 존으로 고정 — Media.capturedAt
    // (CapturedAtConverter, KST 고정 해석)과 동일 기준이라는 프로덕션 전제를 테스트에서도 유지한다.
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-18T03:00:00Z"), ZoneId.of("Asia/Seoul")); // KST 12:00

    @Mock
    private MediaRepository mediaRepository;
    @Mock
    private InspectionRepository inspectionRepository;

    private MediaWriter writer;

    @BeforeEach
    void setUp() {
        writer = new MediaWriter(mediaRepository, inspectionRepository, FIXED_CLOCK);
        when(mediaRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Inspection newInspection(Long id) {
        Inspection inspection = Inspection.builder()
                .facilityId(1L)
                .createdBy(1L)
                .assignedInspectorId(1L)
                .roundNo(1)
                .inspectionDate(LocalDate.of(2026, 8, 18))
                .status(InspectionStatus.CREATED)
                .type(InspectionType.REGULAR)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(inspection, "id", id);
        return inspection;
    }

    private Media newMedia(Long inspectionId, MediaPurpose purpose, LocalDateTime capturedAt) {
        return Media.builder()
                .inspectionId(inspectionId)
                .fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/x.png")
                .mimeSignatureVerified(true)
                .purpose(purpose)
                .capturedAt(capturedAt)
                .build();
    }

    @Test
    void EXIF촬영시각이있으면_그값이_performedAt으로세팅된다() {
        Inspection inspection = newInspection(1L);
        when(inspectionRepository.findAllById(anyIterable())).thenReturn(List.of(inspection));
        LocalDateTime capturedAt = LocalDateTime.of(2026, 8, 18, 9, 30);
        Media media = newMedia(1L, MediaPurpose.INSPECTION_SOURCE, capturedAt);

        writer.saveAll(List.of(media));

        assertThat(inspection.getPerformedAt()).isEqualTo(capturedAt);
    }

    @Test
    void EXIF촬영시각이없으면_업로드시각KST로_대체된다() {
        Inspection inspection = newInspection(1L);
        when(inspectionRepository.findAllById(anyIterable())).thenReturn(List.of(inspection));
        Media media = newMedia(1L, MediaPurpose.INSPECTION_SOURCE, null);

        writer.saveAll(List.of(media));

        LocalDateTime expectedUploadedAt = LocalDateTime.now(FIXED_CLOCK);
        assertThat(inspection.getPerformedAt()).isEqualTo(expectedUploadedAt);
    }

    @Test
    void 같은배치에_여러파일있으면_촬영시각이가장이른값이선택된다() {
        Inspection inspection = newInspection(1L);
        when(inspectionRepository.findAllById(anyIterable())).thenReturn(List.of(inspection));
        LocalDateTime later = LocalDateTime.of(2026, 8, 18, 15, 0);
        LocalDateTime earlier = LocalDateTime.of(2026, 8, 18, 9, 0);

        writer.saveAll(List.of(
                newMedia(1L, MediaPurpose.INSPECTION_SOURCE, later),
                newMedia(1L, MediaPurpose.INSPECTION_SOURCE, earlier)));

        assertThat(inspection.getPerformedAt()).isEqualTo(earlier);
    }

    @Test
    void 이미performedAt이있으면_더늦은후보로는덮지않는다() {
        Inspection inspection = newInspection(1L);
        LocalDateTime existing = LocalDateTime.of(2026, 8, 18, 9, 0);
        inspection.applyPerformedAt(existing);
        when(inspectionRepository.findAllById(anyIterable())).thenReturn(List.of(inspection));
        LocalDateTime laterCandidate = LocalDateTime.of(2026, 8, 18, 15, 0);

        writer.saveAll(List.of(newMedia(1L, MediaPurpose.INSPECTION_SOURCE, laterCandidate)));

        assertThat(inspection.getPerformedAt()).isEqualTo(existing);
    }

    @Test
    void 이미performedAt이있어도_더이른후보면갱신된다() {
        Inspection inspection = newInspection(1L);
        LocalDateTime existing = LocalDateTime.of(2026, 8, 18, 15, 0);
        inspection.applyPerformedAt(existing);
        when(inspectionRepository.findAllById(anyIterable())).thenReturn(List.of(inspection));
        LocalDateTime earlierCandidate = LocalDateTime.of(2026, 8, 18, 9, 0);

        writer.saveAll(List.of(newMedia(1L, MediaPurpose.INSPECTION_SOURCE, earlierCandidate)));

        assertThat(inspection.getPerformedAt()).isEqualTo(earlierCandidate);
    }

    @Test
    void DEFECT_ACTION미디어는_performedAt세팅대상이아니다() {
        Media media = newMedia(1L, MediaPurpose.DEFECT_ACTION, LocalDateTime.of(2026, 8, 18, 9, 0));

        writer.saveAll(List.of(media));

        org.mockito.Mockito.verifyNoInteractions(inspectionRepository);
    }

    @Test
    void inspectionId가없는시설물대표사진은_performedAt세팅대상이아니다() {
        Media facilityPhoto = Media.builder()
                .facilityId(9L)
                .fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/x.png")
                .mimeSignatureVerified(true)
                .purpose(MediaPurpose.INSPECTION_SOURCE)
                .capturedAt(LocalDateTime.of(2026, 8, 18, 9, 0))
                .build();

        writer.saveAll(List.of(facilityPhoto));

        org.mockito.Mockito.verifyNoInteractions(inspectionRepository);
    }
}
