package com.hajacheck.core.analysis.service;

import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import com.hajacheck.core.analysis.dto.AnalysisStatusResponse.FileProgress;
import com.hajacheck.core.analysis.support.AnalysisProgressStore;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.service.DefectWriter;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

/**
 * AI 분석 실행/상태(dev-05-04) 트리거 + 조회 — 실제 분석 루프는 {@link InspectionAnalysisWorker}
 * (별도 @Async 빈, self-invocation 회피 이유는 그 클래스 문서 참고).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionAnalysisService {

    // ANALYZING 고착 복구(코드 리뷰 P2) 시 되돌릴 상태 — 여기 도달했다는 건 이미 이미지가 있다는
    // 뜻이라(images.isEmpty() 가드는 이 시점 이후) "업로드는 끝났고 분석 전"이 가장 정확한 표현이다.
    private static final InspectionStatus RECOVERY_STATUS = InspectionStatus.UPLOADING;

    private final InspectionService inspectionService;
    private final MediaRepository mediaRepository;
    private final DefectRepository defectRepository;
    private final DefectWriter defectWriter;
    private final AnalysisProgressStore progressStore;
    private final InspectionAnalysisWorker worker;

    /**
     * 분석 시작 — 소유권 검증, 이미지 존재 검증, ANALYZING을 원자적으로 선점하고 초기 진행률(전부 대기)을
     * 캐시에 써둔 뒤 비동기 워커에 위임한다. 이 메서드 자체는 워커 완료를 기다리지 않고 즉시 반환한다.
     *
     * <p>코드 리뷰 P2 픽스 3건을 함께 반영한다:
     * <ul>
     *   <li><b>고착 복구</b>: status==ANALYZING인데 진행률 캐시가 없으면(TaskRejectedException 발생
     *       시점 이전 크래시, 워커 진행 중 JVM 재기동 등) 고착으로 간주하고 강제로 되돌려 재시작을 허용한다.</li>
     *   <li><b>원자적 선점</b>: "조회 후 상태 확인 → 별도 UPDATE"가 아니라
     *       {@link InspectionService#tryStartAnalyzing} 단일 조건부 UPDATE로 동시 요청의 이중 실행을 막는다.</li>
     *   <li><b>재분석 멱등화</b>: 선점에 성공하면(=이번 호출이 실제로 분석을 시작함) 기존 하자를
     *       소프트삭제한 뒤 워커를 돌린다 — 완료된 회차 재트리거·경쟁으로 인한 하자 중복 적재 방지.</li>
     * </ul>
     */
    public void startAnalysis(Long requesterUserId, Long companyId, Long inspectionId) {
        Inspection inspection = inspectionService.getOwnedInspectionEntity(requesterUserId, companyId, inspectionId);
        InspectionStatus statusBeforeAnalysis = inspection.getStatus();

        if (statusBeforeAnalysis == InspectionStatus.ANALYZING) {
            if (progressStore.find(inspectionId).isPresent()) {
                throw new BusinessException(ErrorCode.ANALYSIS_ALREADY_RUNNING);
            }
            log.warn("ANALYZING 고착 감지(진행률 캐시 없음) — inspectionId={} 재시작을 허용한다", inspectionId);
            inspectionService.advanceStatus(requesterUserId, companyId, inspectionId, RECOVERY_STATUS);
            statusBeforeAnalysis = RECOVERY_STATUS;
        }

        List<Media> images = mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(inspectionId, MediaFileType.IMAGE);
        if (images.isEmpty()) {
            throw new BusinessException(ErrorCode.ANALYSIS_NO_MEDIA);
        }

        if (!inspectionService.tryStartAnalyzing(requesterUserId, companyId, inspectionId)) {
            // 이 요청과 동시에 들어온 다른 요청이 먼저 선점했다(원자적 조건부 UPDATE 영향 행 0건).
            throw new BusinessException(ErrorCode.ANALYSIS_ALREADY_RUNNING);
        }

        // 재분석 멱등화 — 이 시점부터는 이번 호출이 유일한 실행 주체임이 보장된다.
        defectWriter.softDeleteAllForInspection(inspectionId);

        List<FileProgress> initialFiles = new java.util.ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            initialFiles.add(new FileProgress(images.get(i).getId(), "이미지 " + (i + 1), "waiting", null, "-"));
        }
        progressStore.save(new AnalysisStatusResponse(
                inspectionId, "aiDetection", 0, images.size(), 0, initialFiles, 0, 0,
                emptyGradeMap(), 0));

        try {
            worker.runAsync(requesterUserId, companyId, inspectionId, images, statusBeforeAnalysis);
        } catch (TaskRejectedException e) {
            log.warn("분석 작업 큐 포화 — inspectionId={} 상태를 {}로 되돌린다", inspectionId, statusBeforeAnalysis, e);
            inspectionService.advanceStatus(requesterUserId, companyId, inspectionId, statusBeforeAnalysis);
            progressStore.delete(inspectionId);
            throw new BusinessException(ErrorCode.ANALYSIS_QUEUE_FULL);
        }
    }

    /**
     * 진행 상태 조회 — Redis 캐시를 우선 쓰고, 없으면(TTL 만료·서버 재기동 등) DB로 최선 재구성한다.
     * 재구성 시 실제 진행 중이던 잡의 세부 타임라인은 복원할 수 없지만, 최소한 "무엇이 실제로 맞는지"
     * (분석 완료 여부, 실제 저장된 하자 통계)는 정직하게 보여준다 — 캐시가 없다고 0%로 되돌리지 않는다.
     */
    public AnalysisStatusResponse getStatus(Long requesterUserId, Long companyId, Long inspectionId) {
        Inspection inspection = inspectionService.getOwnedInspectionEntity(requesterUserId, companyId, inspectionId);

        return progressStore.find(inspectionId).orElseGet(() -> rebuildFromDb(inspection));
    }

    private AnalysisStatusResponse rebuildFromDb(Inspection inspection) {
        Long inspectionId = inspection.getId();
        List<Media> images = mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(inspectionId, MediaFileType.IMAGE);

        if (inspection.getStatus() != InspectionStatus.ANALYZED
                && inspection.getStatus() != InspectionStatus.REVIEWED
                && inspection.getStatus() != InspectionStatus.REPORTED) {
            // 분석이 끝난 적 없는 회차 — 캐시도 없으면 "아직 분석 안 됨"이 사실이다(가짜 진행률 금지).
            List<FileProgress> files = new java.util.ArrayList<>(images.size());
            for (int i = 0; i < images.size(); i++) {
                files.add(new FileProgress(images.get(i).getId(), "이미지 " + (i + 1), "waiting", null, "-"));
            }
            return new AnalysisStatusResponse(
                    inspectionId, "upload", 0, images.size(), 0, files, 0, 0, emptyGradeMap(), 0);
        }

        // 완료된 적 있는 회차 — 실제 저장된 defects로 요약을 재구성한다(캐시 TTL 만료 대응).
        List<Defect> defects = defectRepository.findByInspectionIdAndNotDeleted(inspectionId);
        Map<DefectGrade, Integer> gradeCounts = new EnumMap<>(DefectGrade.class);
        int riskyCrackCount = 0;
        Map<Long, Integer> defectCountByMedia = new java.util.HashMap<>();
        for (Defect defect : defects) {
            if (defect.getGrade() != null) {
                gradeCounts.merge(defect.getGrade(), 1, Integer::sum);
            }
            if (defect.getType() == DefectType.CRACK
                    && (defect.getGrade() == DefectGrade.D || defect.getGrade() == DefectGrade.E)) {
                riskyCrackCount++;
            }
            if (defect.getMediaId() != null) {
                defectCountByMedia.merge(defect.getMediaId(), 1, Integer::sum);
            }
        }

        List<FileProgress> files = new java.util.ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            Media media = images.get(i);
            int count = defectCountByMedia.getOrDefault(media.getId(), 0);
            files.add(new FileProgress(media.getId(), "이미지 " + (i + 1), "completed", count, "-"));
        }

        Map<String, Integer> gradeMap = emptyGradeMap();
        gradeCounts.forEach((grade, count) -> gradeMap.put(grade.name(), count));

        return new AnalysisStatusResponse(
                inspectionId, "done", 100, images.size(), images.size(), files,
                defects.size(), riskyCrackCount, gradeMap, 0);
    }

    private Map<String, Integer> emptyGradeMap() {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (DefectGrade grade : DefectGrade.values()) {
            map.put(grade.name(), 0);
        }
        return map;
    }
}
