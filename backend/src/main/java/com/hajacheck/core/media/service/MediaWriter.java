package com.hajacheck.core.media.service;

import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaPurpose;
import com.hajacheck.core.media.repository.MediaRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Media 원자 저장 전담 — 별도 빈으로 분리해 self-invocation 회피(CompanyAccountWriter 와 동일한 이유:
 * 같은 클래스 내부 호출은 @Transactional 프록시가 안 걸리므로, 파일 IO(트랜잭션 밖)를 마친 MediaService가
 * 이 빈을 호출해야 진짜 새 트랜잭션이 열린다). unique/FK 위반은 여기서 그대로 전파되고, 호출부가
 * 저장한 파일들의 보상삭제를 담당한다.
 */
@Component
@RequiredArgsConstructor
public class MediaWriter {

    // EXIF 이상값 방어(V43, #1667, 코드 리뷰 P2) — 카메라 시계 오설정·손상된 메타데이터로 captured_at이
    // "2000년 이전" 같은 명백히 비현실적인 과거를 가리키는 경우가 실제로 관측된다. 그런 값을 그대로
    // performed_at 후보로 쓰면 tie-break 정렬이 엉뚱한 순서로 고정된다 — 업로드 시각으로 안전하게
    // 폴백한다. 미래 시각(업로드 시각보다 나중)도 동일하게 신뢰하지 않는다(경계는
    // resolveCandidate 참고).
    private static final LocalDateTime EXIF_SANITY_FLOOR = LocalDateTime.of(2000, 1, 1, 0, 0);

    private final MediaRepository mediaRepository;
    private final InspectionRepository inspectionRepository;
    // SchedulingConfig 가 제공하는 KST 고정 Clock — Media.capturedAt(KstFixedLocalDateTimeConverter,
    // Asia/Seoul 고정 해석)과 동일한 기준으로 "업로드 시각" 폴백을 계산하고, 테스트가 결정적으로
    // 재현할 수 있게 한다.
    private final Clock clock;

    @Transactional
    public List<Media> saveAll(List<Media> mediaList) {
        List<Media> saved = mediaRepository.saveAll(mediaList);
        applyPerformedAt(saved);
        return saved;
    }

    /**
     * 점검 수행 시각 자동 세팅(V43, #1667) — 이번 배치에 저장된 미디어 중 회차(inspectionId)에 속한
     * INSPECTION_SOURCE만 대상으로 한다(시설물 대표 사진은 inspectionId가 null이라 자동 제외, 조치 후
     * 사진(DEFECT_ACTION)은 실제 점검 수행 이후 등록될 수 있어 제외 — V41 purpose 구분과 동일 원칙).
     * 회차별로 이번 배치의 후보 시각(EXIF captured_at, 없거나 이상값이면 업로드 시각) 중 최솟값을 구해
     * {@link InspectionRepository#applyPerformedAtIfEarlier} 원자적 UPDATE에 위임한다.
     *
     * <p>코드 리뷰 P1 — 예전엔 findAllById로 읽은 Inspection 엔티티를 dirty checking으로 갱신했으나,
     * 배치 업로드 두 건이 동시에 같은 회차에 미디어를 올리면 두 트랜잭션이 같은 스냅샷을 읽고 각자
     * flush하는 lost update가 가능했다. 원자적 UPDATE로 교체해 이 경합을 DB 레벨에서 차단한다.
     */
    private void applyPerformedAt(List<Media> savedMediaList) {
        LocalDateTime uploadedAt = LocalDateTime.now(clock);
        Map<Long, LocalDateTime> earliestCandidateByInspectionId = new HashMap<>();
        for (Media media : savedMediaList) {
            if (media.getInspectionId() == null || media.getPurpose() != MediaPurpose.INSPECTION_SOURCE) {
                continue;
            }
            LocalDateTime candidate = resolveCandidate(media, uploadedAt);
            earliestCandidateByInspectionId.merge(
                    media.getInspectionId(), candidate, (current, next) -> next.isBefore(current) ? next : current);
        }
        earliestCandidateByInspectionId.forEach(inspectionRepository::applyPerformedAtIfEarlier);
    }

    /**
     * EXIF captured_at이 있고 정상 범위({@link #EXIF_SANITY_FLOOR} 이후, uploadedAt 이전 또는 동시)면
     * 그 값을, 없거나 이상값이면 업로드 시각을 후보로 쓴다(코드 리뷰 P2). "미래" 판정은 업로드 시각
     * 기준 상대값이라 카메라 시계가 서버보다 앞서 있어도(흔한 EXIF 오류) 실제 미래로 오인하지 않는다.
     */
    private static LocalDateTime resolveCandidate(Media media, LocalDateTime uploadedAt) {
        LocalDateTime capturedAt = media.getCapturedAt();
        if (capturedAt == null) {
            return uploadedAt;
        }
        if (capturedAt.isBefore(EXIF_SANITY_FLOOR) || capturedAt.isAfter(uploadedAt)) {
            return uploadedAt;
        }
        return capturedAt;
    }
}
