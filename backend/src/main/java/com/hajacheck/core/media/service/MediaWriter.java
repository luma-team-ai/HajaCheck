package com.hajacheck.core.media.service;

import com.hajacheck.core.inspection.entity.Inspection;
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

    private final MediaRepository mediaRepository;
    private final InspectionRepository inspectionRepository;
    // SchedulingConfig 가 제공하는 KST 고정 Clock — Media.capturedAt(CapturedAtConverter, Asia/Seoul
    // 고정 해석)과 동일한 기준으로 "업로드 시각" 폴백을 계산하고, 테스트가 결정적으로 재현할 수 있게 한다.
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
     * 회차별로 이번 배치의 후보 시각(EXIF captured_at, 없으면 업로드 시각) 중 최솟값을 구해
     * {@link Inspection#applyPerformedAt}에 위임한다 — 이미 값이 있으면 더 이른 시각일 때만 갱신된다.
     */
    private void applyPerformedAt(List<Media> savedMediaList) {
        LocalDateTime uploadedAt = LocalDateTime.now(clock);
        Map<Long, LocalDateTime> earliestCandidateByInspectionId = new HashMap<>();
        for (Media media : savedMediaList) {
            if (media.getInspectionId() == null || media.getPurpose() != MediaPurpose.INSPECTION_SOURCE) {
                continue;
            }
            LocalDateTime candidate = media.getCapturedAt() != null ? media.getCapturedAt() : uploadedAt;
            earliestCandidateByInspectionId.merge(
                    media.getInspectionId(), candidate, (current, next) -> next.isBefore(current) ? next : current);
        }
        if (earliestCandidateByInspectionId.isEmpty()) {
            return;
        }
        List<Inspection> inspections = inspectionRepository.findAllById(earliestCandidateByInspectionId.keySet());
        for (Inspection inspection : inspections) {
            inspection.applyPerformedAt(earliestCandidateByInspectionId.get(inspection.getId()));
        }
    }
}
