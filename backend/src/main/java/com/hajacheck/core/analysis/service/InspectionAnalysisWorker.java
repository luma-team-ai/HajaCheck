package com.hajacheck.core.analysis.service;

import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.core.ai.dto.DetectedDefectItem;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import com.hajacheck.core.analysis.dto.AnalysisStatusResponse.FileProgress;
import com.hajacheck.core.analysis.support.AnalysisProgressStore;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.service.DefectWriter;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.global.config.AsyncConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * AI 분석 잡 본체(dev-05-04) — {@link InspectionAnalysisService}가 검증·상태전이·진행률 초기화까지
 * 마친 뒤 이 빈을 호출한다(별도 빈으로 분리한 이유: {@code @Async}는 프록시를 거쳐야 동작해서
 * 같은 빈 안에서 self-invocation하면 무시되고 동기 실행된다 — PasswordResetMailDispatcher와
 * 동일하게 "트리거 메서드"와 "@Async 워커 메서드"를 다른 빈으로 나눈다).
 *
 * <p>회차의 이미지를 순서대로 하나씩 처리한다(병렬 아님) — FastAPI가 CPU 바운드 단일 워커 전제라
 * Spring 쪽에서 병렬로 던져도 처리량이 늘지 않고 대기열만 쌓인다(AsyncConfig 주석 참고). 이미지 1장
 * 실패는 그 이미지만 실패 처리하고 나머지는 계속 진행한다 — 회차 전체를 롤백하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InspectionAnalysisWorker {

    private final InspectionService inspectionService;
    private final FileStorageService fileStorage;
    private final AiProxyService aiProxyService;
    private final DefectWriter defectWriter;
    private final AnalysisProgressStore progressStore;

    /**
     * @param statusBeforeAnalysis 분석 시작 직전 상태(ANALYZING으로 전이되기 전 값) — 코드 리뷰 P2
     *                             픽스: 이미지 전체가 실패하면 이 값으로 되돌려 ANALYZED(완료)로
     *                             오인 전이하지 않는다(InspectionAnalysisService가 큐잉 실패 시
     *                             동일 값으로 롤백하는 것과 대칭 — 정상 완료가 아니면 항상 이 값으로 복귀).
     */
    @Async(AsyncConfig.ANALYSIS_TASK_EXECUTOR)
    public void runAsync(Long requesterUserId, Long companyId, Long inspectionId, List<Media> images,
                          InspectionStatus statusBeforeAnalysis) {
        List<FileProgress> files = new ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            files.add(new FileProgress(images.get(i).getId(), displayName(i), "waiting", null, "-"));
        }

        int detectedDefectCount = 0;
        int riskyCrackCount = 0;
        int failedCount = 0;
        int successCount = 0;
        Map<DefectGrade, Integer> gradeCounts = new EnumMap<>(DefectGrade.class);

        for (int i = 0; i < images.size(); i++) {
            Media media = images.get(i);
            files.set(i, new FileProgress(media.getId(), displayName(i), "analyzing", null, "처리중..."));
            publish(inspectionId, images.size(), i, files, detectedDefectCount, riskyCrackCount,
                    gradeCounts, failedCount);

            Instant startedAt = Instant.now();
            try {
                List<DetectedDefectItem> detections = detect(media);
                List<Defect> toSave = new ArrayList<>(detections.size());
                for (DetectedDefectItem item : detections) {
                    Defect defect = toDefect(inspectionId, media.getId(), item);
                    if (defect == null) {
                        continue; // 알 수 없는 type/grade 문자열 — 방어적으로 스킵(모듈 docstring 대응 Java측)
                    }
                    toSave.add(defect);
                    if (defect.getGrade() != null) {
                        gradeCounts.merge(defect.getGrade(), 1, Integer::sum);
                    }
                    if (defect.getType() == DefectType.CRACK
                            && (defect.getGrade() == DefectGrade.D || defect.getGrade() == DefectGrade.E)) {
                        riskyCrackCount++;
                    }
                }
                defectWriter.saveAll(toSave);
                detectedDefectCount += toSave.size();
                successCount++;

                String elapsed = formatElapsed(startedAt);
                files.set(i, new FileProgress(media.getId(), displayName(i), "completed", toSave.size(), elapsed));
            } catch (Exception e) {
                // 이미지 1장 실패를 격리 — 회차 전체를 중단하지 않는다.
                failedCount++;
                log.warn("AI 분석 실패 — inspectionId={} mediaId={} exception={}",
                        inspectionId, media.getId(), e.getClass().getSimpleName());
                files.set(i, new FileProgress(media.getId(), displayName(i), "failed", null, "오류"));
            }

            publish(inspectionId, images.size(), i + 1, files, detectedDefectCount, riskyCrackCount,
                    gradeCounts, failedCount);
        }

        // 코드 리뷰 P2 픽스 — 성공 0건(AI 서버 전면 다운 등)이면 ANALYZED(완료)로 전이하지 않는다.
        // 그대로 두면 프론트가 100%/"분석 완료"로 표시해 검수 단계 진입을 허용해버린다(아무것도
        // 분석되지 않았는데 완료로 오인). 상태를 시작 전으로 되돌려 재시도 가능하게 남긴다.
        if (successCount == 0 && !images.isEmpty()) {
            log.warn("AI 분석 전체 실패 — inspectionId={} totalImages={} — ANALYZED 전이를 건너뛰고 {}로 되돌린다",
                    inspectionId, images.size(), statusBeforeAnalysis);
            inspectionService.advanceStatus(requesterUserId, companyId, inspectionId, statusBeforeAnalysis);
            AnalysisStatusResponse failedProgress = new AnalysisStatusResponse(
                    inspectionId, "aiDetection", 0, images.size(), 0, files,
                    detectedDefectCount, riskyCrackCount, toGradeCountMap(gradeCounts), failedCount);
            progressStore.save(failedProgress);
            return;
        }

        inspectionService.advanceStatus(requesterUserId, companyId, inspectionId, InspectionStatus.ANALYZED);
        AnalysisStatusResponse done = new AnalysisStatusResponse(
                inspectionId, "done", 100, images.size(), images.size(), files,
                detectedDefectCount, riskyCrackCount, toGradeCountMap(gradeCounts), failedCount);
        progressStore.save(done);
    }

    private List<DetectedDefectItem> detect(Media media) {
        byte[] bytes = fileStorage.read(media.getOriginalUrl());
        String base64 = Base64.getEncoder().encodeToString(bytes);
        return aiProxyService.detectDefects(base64);
    }

    private Defect toDefect(Long inspectionId, Long mediaId, DetectedDefectItem item) {
        DefectType type;
        DefectGrade grade;
        try {
            type = DefectType.valueOf(item.type());
            grade = item.grade() == null ? null : DefectGrade.valueOf(item.grade());
        } catch (IllegalArgumentException e) {
            log.warn("AI 서버가 알 수 없는 type/grade를 반환 — type={} grade={}", item.type(), item.grade());
            return null;
        }
        return Defect.builder()
                .inspectionId(inspectionId)
                .mediaId(mediaId)
                .type(type)
                .bboxX(item.bboxX())
                .bboxY(item.bboxY())
                .bboxW(item.bboxW())
                .bboxH(item.bboxH())
                .confidence(item.confidence())
                .grade(grade)
                .build();
    }

    private void publish(Long inspectionId, int total, int analyzedCount, List<FileProgress> files,
                          int detectedDefectCount, int riskyCrackCount, Map<DefectGrade, Integer> gradeCounts,
                          int failedCount) {
        int percent = total == 0 ? 0 : (int) Math.round(analyzedCount * 100.0 / total);
        AnalysisStatusResponse progress = new AnalysisStatusResponse(
                inspectionId, "aiDetection", percent, total, analyzedCount, List.copyOf(files),
                detectedDefectCount, riskyCrackCount, toGradeCountMap(gradeCounts), failedCount);
        progressStore.save(progress);
    }

    private Map<String, Integer> toGradeCountMap(Map<DefectGrade, Integer> gradeCounts) {
        // 프론트 표시는 등급별 "건수"면 충분 — 퍼센트 변환은 프론트가 합계로 나눠서 한다.
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        for (DefectGrade grade : DefectGrade.values()) {
            result.put(grade.name(), gradeCounts.getOrDefault(grade, 0));
        }
        return result;
    }

    private String displayName(int index) {
        // 원본 파일명은 저장하지 않는다(LocalFileStorage: 보안상 UUID로만 저장, PRD FR-2) — 순번 라벨만 표시.
        return "이미지 " + (index + 1);
    }

    private String formatElapsed(Instant startedAt) {
        long millis = Duration.between(startedAt, Instant.now()).toMillis();
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }
}
