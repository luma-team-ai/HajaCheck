package com.hajacheck.core.analysis.service;

import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import com.hajacheck.core.analysis.dto.AnalysisStatusResponse.FileProgress;
import com.hajacheck.core.analysis.support.AnalysisProgressStore;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.DefectRevisionRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    // 재분석 허용 소스 상태(코드 리뷰 P1, 제품 결정) — REVIEWED/REPORTED는 목록에서 뺀다.
    // 재분석은 워커가 기존 하자를 소프트삭제하므로, 사람이 검수·확정한 최종 상태 회차에서 허용하면
    // 무보상 데이터 유실 표면이 된다. ANALYZING은 별도 고착 복구 분기에서 다룬다.
    private static final java.util.Set<InspectionStatus> ANALYSIS_ALLOWED_SOURCE_STATUSES = java.util.EnumSet.of(
            InspectionStatus.CREATED, InspectionStatus.UPLOADING, InspectionStatus.ANALYZED);

    // 진행률 캐시가 종료됐다고 보는 stage(코드 리뷰 P2) — 이 상태면 고착이 아니라 정상 종료다.
    private static final Set<String> TERMINAL_STAGES = Set.of("done", "failed");

    // 고착 판정 임계값(코드 리뷰 P2, 제품 확인 완료) — ANALYZING인데 진행률 캐시(하트비트)가 이보다
    // 오래 갱신 안 됐으면 워커가 크래시(JVM 재기동·OOM 등)한 것으로 본다. PRD 목표(100장 10분)의
    // 정상 진행 간격보다 충분히 커서 정상 진행 중인 잡을 오탐하지 않는다.
    private static final Duration STUCK_HEARTBEAT_THRESHOLD = Duration.ofMinutes(5);

    private final InspectionService inspectionService;
    private final MediaRepository mediaRepository;
    private final DefectRepository defectRepository;
    private final DefectRevisionRepository defectRevisionRepository;
    private final AnalysisProgressStore progressStore;
    private final InspectionAnalysisWorker worker;

    /**
     * 분석 시작 — 소유권 검증, 이미지 존재 검증, ANALYZING을 원자적으로 선점하고 초기 진행률(전부 대기)을
     * 캐시에 써둔 뒤 비동기 워커에 위임한다. 이 메서드 자체는 워커 완료를 기다리지 않고 즉시 반환한다.
     *
     * <p>코드 리뷰 P1/P2 픽스를 함께 반영한다:
     * <ul>
     *   <li><b>고착 복구</b>: status==ANALYZING인데 진행률 캐시가 없거나(TaskRejectedException 발생
     *       시점 이전 크래시 등) 캐시는 있지만 하트비트가 {@link #STUCK_HEARTBEAT_THRESHOLD}보다
     *       오래 갱신 안 됐으면(워커가 JVM 재기동·OOM 등으로 죽었지만 TTL 6시간짜리 캐시는 살아있는
     *       경우) 고착으로 간주하고 강제로 되돌려 재시작을 허용한다. (P1) "캐시 부재" 판정은 원자적
     *       선점 성공과 캐시 기록 사이에 오래 걸리는 작업이 끼면 정상 진행 중인 요청을 다른 요청이
     *       고착으로 오판해 이중 실행될 수 있다 — 그래서 선점 성공 직후 곧바로(다른 무거운 작업
     *       없이) 캐시를 써서 그 창을 인메모리 리스트 구성 수준(사실상 무시 가능)으로 좁힌다.
     *       예전엔 이 사이에 소프트삭제(수십~수백 ms, DB 트랜잭션)가 끼어 있어 더블클릭·재시도로
     *       현실적으로 도달 가능한 경쟁이었다.</li>
     *   <li><b>원자적 선점</b>: "조회 후 상태 확인 → 별도 UPDATE"가 아니라
     *       {@link InspectionService#tryStartAnalyzing} 단일 조건부 UPDATE로 동시 요청의 이중 실행을 막는다.</li>
     *   <li><b>재분석 멱등화</b>(P2): 기존 하자 소프트삭제는 더 이상 이 메서드가 하지 않는다 —
     *       {@link InspectionAnalysisWorker}가 실제로 최소 1건 탐지에 성공한 시점에 지연 실행한다.
     *       이 메서드에서 미리 지워버리면, 이후 큐 포화({@link TaskRejectedException})나 워커 전체
     *       실패로 롤백되는 경우 이미 커밋된 소프트삭제는 보상되지 않아 검수 완료된 회차의 하자가
     *       영구 유실된다 — 실행이 실제로 결실을 맺기 전까지는 기존 데이터를 건드리지 않는다.</li>
     *   <li><b>재분석 소스 상태 가드</b>(P1, 제품 결정): {@link #ANALYSIS_ALLOWED_SOURCE_STATUSES}에
     *       없는 상태(REVIEWED/REPORTED)에서는 {@link ErrorCode#ANALYSIS_NOT_ALLOWED}로 거부한다.
     *       가드가 없으면 검수 완료·보고서화된 회차도 재분석 트리거만으로 사람이 조정한 하자가
     *       무보상으로 삭제되고 상태가 ANALYZED로 역행해 보고서 확정 워크플로우가 깨진다.</li>
     *   <li><b>ANALYZED 리비전 가드</b>(P2, 제품 결정): 소스 상태가 ANALYZED이면 이 회차의 하자
     *       중 사람이 조정(defect_revisions 존재)한 것이 하나라도 있는지 확인해, 있으면 REVIEWED/
     *       REPORTED와 동일하게 {@link ErrorCode#ANALYSIS_NOT_ALLOWED}로 거부한다. 위 소스 상태
     *       가드는 REVIEWED/REPORTED만 "사람이 확정한 최종 상태"로 보호하는데, ANALYZED 단계에서도
     *       검수 완료 전에 등급 등을 조정하는 워크플로우가 있어 같은 무보상 유실 위험을 가진다.</li>
     * </ul>
     */
    public void startAnalysis(Long requesterUserId, Long companyId, Long inspectionId) {
        Inspection inspection = inspectionService.getOwnedInspectionEntity(requesterUserId, companyId, inspectionId);
        InspectionStatus statusBeforeAnalysis = inspection.getStatus();

        if (statusBeforeAnalysis == InspectionStatus.ANALYZING) {
            String stuckReason = stuckReason(progressStore.find(inspectionId));
            if (stuckReason == null) {
                throw new BusinessException(ErrorCode.ANALYSIS_ALREADY_RUNNING);
            }
            log.warn("ANALYZING 고착 감지({}) — inspectionId={} 재시작을 허용한다", stuckReason, inspectionId);
            inspectionService.advanceStatus(requesterUserId, companyId, inspectionId, RECOVERY_STATUS);
            statusBeforeAnalysis = RECOVERY_STATUS;
        }

        if (!ANALYSIS_ALLOWED_SOURCE_STATUSES.contains(statusBeforeAnalysis)) {
            // 코드 리뷰 P1 — REVIEWED/REPORTED(검수·보고서 확정) 회차는 재분석을 허용하지 않는다.
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_ALLOWED);
        }

        if (statusBeforeAnalysis == InspectionStatus.ANALYZED && hasUserRevisedDefects(inspectionId)) {
            // 코드 리뷰 P2(제품 결정) — ANALYZED 단계에서도 사람이 하자를 조정했으면 재분석을 막는다.
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_ALLOWED);
        }

        List<Media> images = mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(inspectionId, MediaFileType.IMAGE);
        if (images.isEmpty()) {
            throw new BusinessException(ErrorCode.ANALYSIS_NO_MEDIA);
        }

        if (!inspectionService.tryStartAnalyzing(requesterUserId, companyId, inspectionId)) {
            // 이 요청과 동시에 들어온 다른 요청이 먼저 선점했다(원자적 조건부 UPDATE 영향 행 0건).
            throw new BusinessException(ErrorCode.ANALYSIS_ALREADY_RUNNING);
        }

        // P1 — 선점 성공과 캐시 기록 사이에 무거운 작업을 두지 않는다(클래스 javadoc 참고).
        List<FileProgress> initialFiles = new java.util.ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            initialFiles.add(new FileProgress(images.get(i).getId(), "이미지 " + (i + 1), "waiting", null, "-"));
        }
        progressStore.save(new AnalysisStatusResponse(
                inspectionId, "aiDetection", 0, images.size(), 0, initialFiles, 0, 0,
                emptyGradeMap(), 0, Instant.now()));

        try {
            worker.runAsync(requesterUserId, companyId, inspectionId, images, statusBeforeAnalysis);
        } catch (TaskRejectedException e) {
            // 코드 리뷰 P2 — analysisTaskExecutor는 테넌트 구분 없는 전역 공유 풀이라(AsyncConfig),
            // 어떤 회사가 큐를 채워 다른 회사까지 503을 받게 됐는지 나중에 로그로 추적할 수 있도록
            // companyId를 남긴다(지금은 실제 부하 패턴을 관측하는 단계 — 회사별 격리는 별도 스코프).
            log.warn("분석 작업 큐 포화 — inspectionId={} companyId={} 상태를 {}로 되돌린다",
                    inspectionId, companyId, statusBeforeAnalysis, e);
            inspectionService.advanceStatus(requesterUserId, companyId, inspectionId, statusBeforeAnalysis);
            progressStore.delete(inspectionId);
            throw new BusinessException(ErrorCode.ANALYSIS_QUEUE_FULL);
        }
    }

    /**
     * 진행 상태 조회 — Redis 캐시를 우선 쓰고, 없으면(TTL 만료·서버 재기동 등) DB로 최선 재구성한다.
     * 재구성 시 실제 진행 중이던 잡의 세부 타임라인은 복원할 수 없지만, 최소한 "무엇이 실제로 맞는지"
     * (분석 완료 여부, 실제 저장된 하자 통계)는 정직하게 보여준다 — 캐시가 없다고 0%로 되돌리지 않는다.
     *
     * <p>캐시는 있지만 하트비트가 오래돼(코드 리뷰 P2, {@link #isCacheStale}) 고착으로 보이면
     * stage만 "failed"로 바꿔 반환한다 — 이 메서드는 읽기 전용(GET)이라 DB/Redis를 실제로 고치지는
     * 않는다(부작용 없음 원칙). 사용자가 화면의 재시도 버튼을 눌러 {@link #startAnalysis}를 다시
     * 호출해야 실제 상태 복구·재시작이 일어난다 — 거기서도 같은 {@link #isCacheStale} 기준을 쓴다.
     */
    public AnalysisStatusResponse getStatus(Long requesterUserId, Long companyId, Long inspectionId) {
        Inspection inspection = inspectionService.getOwnedInspectionEntity(requesterUserId, companyId, inspectionId);

        return progressStore.find(inspectionId)
                .map(cached -> isCacheStale(cached) ? cached.withStage("failed") : cached)
                .orElseGet(() -> rebuildFromDb(inspection));
    }

    /**
     * 진행률 캐시가 고착됐는지 판정한다(코드 리뷰 P2, 사용자 확인 완료) — {@link #TERMINAL_STAGES}로
     * 이미 종료된 캐시는 고착이 아니라 정상 종료다. 그 외(진행 중으로 보이는) 캐시는 하트비트
     * ({@link AnalysisStatusResponse#updatedAt})가 {@link #STUCK_HEARTBEAT_THRESHOLD}보다 오래
     * 갱신 안 됐으면 워커 크래시로 본다.
     */
    private boolean isCacheStale(AnalysisStatusResponse cached) {
        if (TERMINAL_STAGES.contains(cached.stage())) {
            return false;
        }
        return Duration.between(cached.updatedAt(), Instant.now()).compareTo(STUCK_HEARTBEAT_THRESHOLD) > 0;
    }

    /**
     * ANALYZED 회차의 현재 비삭제 하자 중 사람이 조정(defect_revisions 존재)한 것이 있는지
     * 확인한다(코드 리뷰 P2, 제품 결정 완료).
     */
    private boolean hasUserRevisedDefects(Long inspectionId) {
        List<Long> defectIds = defectRepository.findByInspectionIdAndNotDeleted(inspectionId).stream()
                .map(Defect::getId)
                .toList();
        return !defectIds.isEmpty() && defectRevisionRepository.existsByDefectIdIn(defectIds);
    }

    /**
     * ANALYZING 고착 여부와 사유를 함께 판정한다(코드 리뷰 P2, 사용자 확인 완료) — 반환값이
     * {@code null}이면 고착이 아니다(=진행 중인 것으로 보고 ALREADY_RUNNING). non-null이면 그
     * 사유 문자열이고 호출부가 고착 복구를 진행한다.
     *
     * <p>캐시가 있으면 하트비트({@link #isCacheStale})로만 판단한다. 캐시가 "없으면" 두 가지
     * 원인이 구분 안 된다 — ①TTL 만료·크래시로 진짜 없음(고착) ②Redis 자체가 지금 불안정해서
     * find()가 fail-soft로 empty를 돌려준 것(진행 중인 잡을 오판할 위험). {@link
     * AnalysisProgressStore#isAvailable}로 저장소가 정상임을 확인했을 때만 "진짜 없음"으로 보고
     * 고착 복구를 허용한다 — Redis가 죽어 있으면 판단을 유보하고 진행 중이라고 보수적으로 본다
     * (저장소가 복구되면 다음 재시도부터 정상적으로 고착 판정이 동작한다).
     */
    private String stuckReason(Optional<AnalysisStatusResponse> cached) {
        if (cached.isPresent()) {
            return isCacheStale(cached.get()) ? "캐시 하트비트 지연" : null;
        }
        return progressStore.isAvailable() ? "진행률 캐시 없음" : null;
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
                    inspectionId, "upload", 0, images.size(), 0, files, 0, 0, emptyGradeMap(), 0, Instant.now());
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
                defects.size(), riskyCrackCount, gradeMap, 0, Instant.now());
    }

    private Map<String, Integer> emptyGradeMap() {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (DefectGrade grade : DefectGrade.values()) {
            map.put(grade.name(), 0);
        }
        return map;
    }
}
