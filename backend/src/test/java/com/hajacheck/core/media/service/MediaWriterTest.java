package com.hajacheck.core.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import com.hajacheck.core.media.entity.MediaPurpose;
import com.hajacheck.core.media.repository.MediaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * MediaWriter.applyPerformedAt(V43, #1667) 단위 검증 — 회차의 INSPECTION_SOURCE 미디어 저장 직후
 * 배치 내 회차별 최솟값 candidate를 계산해 {@link InspectionRepository#applyPerformedAtIfEarlier}
 * 원자적 UPDATE를 호출하는 로직. 코드 리뷰 P1-1(엔티티 read-modify-write → 원자적 UPDATE 교체) 이후
 * 실제 "더 이른 값만 갱신"이라는 lost-update 방지 의미론은 그 UPDATE의 WHERE 절 자체(DB 레벨)가
 * 보장하므로, 이 단위 테스트는 MediaWriter가 그 원자적 메서드를 **올바른 (id, candidate) 인자로**
 * 호출하는지만 검증한다(실제 DB 경합·WHERE 조건 검증은 InspectionRepositoryTest 참고).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaWriterTest {

    // SchedulingConfig.clock()과 동일하게 Asia/Seoul(KST) 존으로 고정 — Media.capturedAt
    // (KstFixedLocalDateTimeConverter, KST 고정 해석)과 동일 기준이라는 프로덕션 전제를 테스트에서도 유지한다.
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-18T03:00:00Z"), ZoneId.of("Asia/Seoul")); // KST 12:00
    private static final LocalDateTime UPLOADED_AT = LocalDateTime.now(FIXED_CLOCK);

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
    void EXIF촬영시각이있으면_그값으로원자적UPDATE가호출된다() {
        LocalDateTime capturedAt = LocalDateTime.of(2026, 8, 18, 9, 30);
        Media media = newMedia(1L, MediaPurpose.INSPECTION_SOURCE, capturedAt);

        writer.saveAll(List.of(media));

        verify(inspectionRepository).applyPerformedAtIfEarlier(1L, capturedAt);
    }

    @Test
    void EXIF촬영시각이없으면_업로드시각KST로_원자적UPDATE가호출된다() {
        Media media = newMedia(1L, MediaPurpose.INSPECTION_SOURCE, null);

        writer.saveAll(List.of(media));

        verify(inspectionRepository).applyPerformedAtIfEarlier(1L, UPLOADED_AT);
    }

    @Test
    void 같은배치에_여러파일있으면_촬영시각이가장이른값으로_1회만호출된다() {
        LocalDateTime later = LocalDateTime.of(2026, 8, 18, 15, 0);
        LocalDateTime earlier = LocalDateTime.of(2026, 8, 18, 9, 0);

        writer.saveAll(List.of(
                newMedia(1L, MediaPurpose.INSPECTION_SOURCE, later),
                newMedia(1L, MediaPurpose.INSPECTION_SOURCE, earlier)));

        verify(inspectionRepository, times(1)).applyPerformedAtIfEarlier(1L, earlier);
    }

    @Test
    void 서로다른회차는_회차별로각각원자적UPDATE가호출된다() {
        LocalDateTime capturedAt1 = LocalDateTime.of(2026, 8, 18, 9, 0);
        LocalDateTime capturedAt2 = LocalDateTime.of(2026, 8, 18, 10, 0);

        writer.saveAll(List.of(
                newMedia(1L, MediaPurpose.INSPECTION_SOURCE, capturedAt1),
                newMedia(2L, MediaPurpose.INSPECTION_SOURCE, capturedAt2)));

        verify(inspectionRepository).applyPerformedAtIfEarlier(1L, capturedAt1);
        verify(inspectionRepository).applyPerformedAtIfEarlier(2L, capturedAt2);
    }

    @Test
    void DEFECT_ACTION미디어는_원자적UPDATE호출대상이아니다() {
        Media media = newMedia(1L, MediaPurpose.DEFECT_ACTION, LocalDateTime.of(2026, 8, 18, 9, 0));

        writer.saveAll(List.of(media));

        verify(inspectionRepository, never()).applyPerformedAtIfEarlier(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void inspectionId가없는시설물대표사진은_원자적UPDATE호출대상이아니다() {
        Media facilityPhoto = Media.builder()
                .facilityId(9L)
                .fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/x.png")
                .mimeSignatureVerified(true)
                .purpose(MediaPurpose.INSPECTION_SOURCE)
                .capturedAt(LocalDateTime.of(2026, 8, 18, 9, 0))
                .build();

        writer.saveAll(List.of(facilityPhoto));

        verify(inspectionRepository, never()).applyPerformedAtIfEarlier(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    // ── EXIF 이상값 방어(코드 리뷰 P2) ──────────────────────────────────────

    @Test
    void EXIF촬영시각이업로드시각보다미래면_무시하고업로드시각으로폴백한다() {
        LocalDateTime future = UPLOADED_AT.plusDays(1);
        Media media = newMedia(1L, MediaPurpose.INSPECTION_SOURCE, future);

        writer.saveAll(List.of(media));

        verify(inspectionRepository).applyPerformedAtIfEarlier(1L, UPLOADED_AT);
    }

    @Test
    void EXIF촬영시각이2000년이전이면_무시하고업로드시각으로폴백한다() {
        LocalDateTime tooOld = LocalDateTime.of(1999, 12, 31, 23, 59);
        Media media = newMedia(1L, MediaPurpose.INSPECTION_SOURCE, tooOld);

        writer.saveAll(List.of(media));

        verify(inspectionRepository).applyPerformedAtIfEarlier(1L, UPLOADED_AT);
    }

    @Test
    void EXIF촬영시각이업로드시각과정확히같으면_미래로취급하지않고그값을쓴다() {
        Media media = newMedia(1L, MediaPurpose.INSPECTION_SOURCE, UPLOADED_AT);

        writer.saveAll(List.of(media));

        verify(inspectionRepository).applyPerformedAtIfEarlier(1L, UPLOADED_AT);
    }

    @Test
    void EXIF촬영시각이정확히2000년경계면_이상값으로취급하지않고그값을쓴다() {
        LocalDateTime floor = LocalDateTime.of(2000, 1, 1, 0, 0);
        Media media = newMedia(1L, MediaPurpose.INSPECTION_SOURCE, floor);

        writer.saveAll(List.of(media));

        verify(inspectionRepository).applyPerformedAtIfEarlier(1L, floor);
    }

    @Test
    void 이상값과정상값이섞인배치는_정상값기준최솟값으로호출된다() {
        // 이상값(2000년 이전)이 산술적으로는 더 "이르지만" 업로드 시각으로 정규화되므로, 정상 EXIF
        // 값(9시)이 최솟값이 된다 — 정규화가 merge(최솟값 계산)보다 먼저 적용돼야 함을 검증.
        LocalDateTime bogus = LocalDateTime.of(1970, 1, 1, 0, 0);
        LocalDateTime normal = LocalDateTime.of(2026, 8, 18, 9, 0);

        writer.saveAll(List.of(
                newMedia(1L, MediaPurpose.INSPECTION_SOURCE, bogus),
                newMedia(1L, MediaPurpose.INSPECTION_SOURCE, normal)));

        verify(inspectionRepository).applyPerformedAtIfEarlier(1L, normal);
    }

    @Test
    void 저장된미디어목록은_원자적UPDATE호출과무관하게그대로반환된다() {
        Media media = newMedia(1L, MediaPurpose.INSPECTION_SOURCE, LocalDateTime.of(2026, 8, 18, 9, 0));

        List<Media> result = writer.saveAll(List.of(media));

        assertThat(result).containsExactly(media);
    }

    // 배치 저장→원자적 UPDATE 호출 인자를 명시적으로 캡처해 재확인(위 검증들의 보강 — id/candidate
    // 쌍이 뒤섞이지 않는지 캡처 기반으로 다시 고정한다).
    @Test
    void 회차별candidate가뒤섞이지않고_각각의id로전달된다() {
        LocalDateTime capturedAt1 = LocalDateTime.of(2026, 8, 18, 9, 0);
        LocalDateTime capturedAt2 = LocalDateTime.of(2026, 8, 18, 10, 0);
        writer.saveAll(List.of(
                newMedia(10L, MediaPurpose.INSPECTION_SOURCE, capturedAt1),
                newMedia(20L, MediaPurpose.INSPECTION_SOURCE, capturedAt2)));

        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<LocalDateTime> candidateCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(inspectionRepository, times(2)).applyPerformedAtIfEarlier(idCaptor.capture(), candidateCaptor.capture());

        assertThat(idCaptor.getAllValues()).containsExactlyInAnyOrder(10L, 20L);
        int indexOf10 = idCaptor.getAllValues().indexOf(10L);
        int indexOf20 = idCaptor.getAllValues().indexOf(20L);
        assertThat(candidateCaptor.getAllValues().get(indexOf10)).isEqualTo(capturedAt1);
        assertThat(candidateCaptor.getAllValues().get(indexOf20)).isEqualTo(capturedAt2);
    }
}
