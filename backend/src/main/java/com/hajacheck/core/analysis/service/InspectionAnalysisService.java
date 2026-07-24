package com.hajacheck.core.analysis.service;

import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import com.hajacheck.core.analysis.dto.AnalysisStatusResponse.FileProgress;
import com.hajacheck.core.analysis.support.AnalysisProgressStore;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.DefectRevisionRepository;
import com.hajacheck.core.defect.service.DefectRevisionService;
import com.hajacheck.core.defect.service.DefectWriter;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
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

    // 수동 생성 하자 판정 sentinel(코드 리뷰 P1 3차) — DefectRevisionService.createManualDefect()가
    // 스키마 마이그레이션 없이 AI/사람 구분을 표시하려고 쓰는 값과 동일해야 한다(그 쪽 confidence(1.0)
    // 리터럴과 반드시 일치 — 어긋나면 이 가드가 조용히 무력화된다). 근본 해결은 AI/사람 생성 구분
    // 컬럼 추가(#644), 그 전까지의 최선 근사치.
    private static final Double MANUAL_DEFECT_CONFIDENCE_SENTINEL = 1.0;

    // 회사별 분석 동시 실행 상한(코드 리뷰 P2 4차) — analysisTaskExecutor(AsyncConfig, 전역 공유
    // core=max=2·queue=20)를 한 회사가 대량 요청으로 독점하면 다른 회사까지 ANALYSIS_QUEUE_FULL을
    // 받는 noisy-neighbor 표면이다. 코어 스레드 수(2)와 동일하게 맞춰, 한 회사가 큐 슬롯 다수를
    // 선점해도 최소한 스레드 하나만큼은 다른 회사 몫으로 남도록 한다. 완벽한 격리(파티셔닝)는 아닌
    // 최소 방어선 — 정밀한 공정성이 필요해지면 회사별 큐 분리로 승격할 것.
    private static final long PER_COMPANY_CONCURRENT_ANALYSIS_LIMIT = 2;

    private final InspectionService inspectionService;
    private final InspectionRepository inspectionRepository;
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
     *   <li><b>워커 펜싱</b>(P1): 고착 복구는 원본 워커가 실제로 죽었는지 확인할 수 없다 — 하트비트
     *       판정(고착 판정)이 오탐(GC 정지, 분석 실행기 큐 적체로 첫 이미지 처리가 늦게 시작되는
     *       경우 등)이면 원본 워커가 여전히 살아 돌고 있는 채로 재선점이 새 워커를 하나 더 띄운다.
     *       이를 막기 위해 재선점(이 메서드 호출)마다 새 세대 토큰을 발급해 {@link AnalysisProgressStore}에
     *       기록하고 워커에 함께 넘긴다 — {@link InspectionAnalysisWorker}는 DB에 쓰기 직전마다 자신의
     *       토큰과 "현재" 토큰을 비교해, 다르면(추월당함) 스스로 중단한다.</li>
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

        // 코드 리뷰 P2 4차 — 공유 실행기 큐에 넣기 전에 회사별 동시 실행 상한을 먼저 강제한다.
        // 이 카운트는 원자적이지 않다(조회 후 아래에서 별도 UPDATE) — 동시에 여러 요청이 들어오면
        // 상한을 살짝 넘을 수 있지만, 정확한 개수 제한이 목적이 아니라 "한 회사가 큐 전체를 독점하는
        // 것"을 막는 최소 방어선이라 이 정도 여유는 허용한다(엄격한 단일실행 보장이 필요한
        // startAnalyzingIfNotRunning의 원자적 조건부 UPDATE와는 목적이 다르다).
        long companyActiveAnalyses = inspectionRepository.countByFacilityCompanyIdAndStatus(
                companyId, InspectionStatus.ANALYZING);
        if (companyActiveAnalyses >= PER_COMPANY_CONCURRENT_ANALYSIS_LIMIT) {
            log.warn("회사별 분석 동시 실행 상한 초과 — companyId={} activeAnalyses={} limit={}",
                    companyId, companyActiveAnalyses, PER_COMPANY_CONCURRENT_ANALYSIS_LIMIT);
            throw new BusinessException(ErrorCode.ANALYSIS_COMPANY_CONCURRENCY_LIMIT);
        }

        if (!inspectionService.tryStartAnalyzing(
                requesterUserId, companyId, inspectionId, ANALYSIS_ALLOWED_SOURCE_STATUSES)) {
            // 원자적 조건부 UPDATE 영향 행 0건 — 다른 요청이 먼저 선점했거나, 사전 체크 이후 허용되지
            // 않은 소스 상태(REVIEWED/REPORTED 등)로 전이됐다(코드 리뷰 P1 10차 — WHERE가 허용
            // 소스 상태를 강제하므로 그 TOCTOU에서도 사람 확정 하자가 소프트삭제로 유실되지 않는다).
            throw new BusinessException(ErrorCode.ANALYSIS_ALREADY_RUNNING);
        }

        // 워커 펜싱용 세대 토큰 발급(코드 리뷰 P1) — 선점(이 메서드 호출)마다 새로 발급한다. 고착
        // 복구로 재선점한 경우, 하트비트 오탐으로 원본 워커가 실제로는 아직 살아 돌고 있어도 이
        // 새 토큰이 "현재" 토큰이 되므로, 원본 워커는 다음 DB 쓰기 직전 자신의(옛) 토큰과 불일치를
        // 확인하고 스스로 중단한다(InspectionAnalysisWorker 참고).
        String generation = java.util.UUID.randomUUID().toString();
        progressStore.saveGeneration(inspectionId, generation);

        // P1 — 선점 성공과 캐시 기록 사이에 무거운 작업을 두지 않는다(클래스 javadoc 참고).
        List<FileProgress> initialFiles = new java.util.ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            initialFiles.add(new FileProgress(images.get(i).getId(), "이미지 " + (i + 1), "waiting", null, "-"));
        }
        progressStore.save(new AnalysisStatusResponse(
                inspectionId, "aiDetection", 0, images.size(), 0, initialFiles, 0, 0,
                emptyGradeMap(), 0, Instant.now()));

        try {
            worker.runAsync(requesterUserId, companyId, inspectionId, images, statusBeforeAnalysis, generation);
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
     * ANALYZED 회차의 현재 비삭제 하자 중 사람이 손댄 것이 있는지 확인한다(코드 리뷰 P2, 제품
     * 결정 완료 / 코드 리뷰 P1 3차 확장).
     *
     * <p>"사람이 손댄" 판정은 두 경로를 모두 본다:
     * <ol>
     *   <li><b>검수 조정</b>: {@code defect_revisions} 존재(등급 조정·오탐 삭제 등 기존 하자 수정 이력).</li>
     *   <li><b>수동 생성</b>(코드 리뷰 P1 3차): {@link DefectRevisionService#createManualDefect}로
     *       검수자가 AI가 놓친 하자를 직접 추가한 경우. 이 경로는 {@code defect_revisions}에 아무
     *       행도 남기지 않아(신규 생성이라 "수정 이력"이 아님) 1번만으로는 걸러지지 않았다 — 그 상태로
     *       재분석하면 {@link InspectionAnalysisWorker}가 {@link DefectWriter#softDeleteAllForInspectionThenSave}
     *       (비삭제 행 전체 대상)로 방금 사람이 입력한 하자까지 함께 소프트삭제해버려 무보상으로
     *       유실된다. {@code createManualDefect}는 상태 제한이 없어 ANALYZED 회차에도 수동 추가가
     *       가능하므로 현실적으로 도달 가능한 경로다.</li>
     * </ol>
     *
     * <p>수동 생성 판정은 {@code confidence == }{@link #MANUAL_DEFECT_CONFIDENCE_SENTINEL} —
     * {@code DefectRevisionService.createManualDefect}가 스키마 마이그레이션 없이 AI/사람 구분을
     * 표시하려고 쓰는 sentinel 값이다({@code confidence(1.0)} 리터럴, 부동소수점 반올림 오차 없이
     * 정확히 일치). AI 탐지 결과는 YOLO confidence를 그대로 저장하므로 실무상 정확히 1.0이 나올
     * 일은 사실상 없지만, 완전히 배제할 수는 없는 임시방편이다 — 근본 해결은 AI/사람 생성 구분
     * 컬럼 추가(#644)이며, 그 전까지의 최선 근사치로 이 sentinel을 쓴다.
     */
    private boolean hasUserRevisedDefects(Long inspectionId) {
        List<Defect> defects = defectRepository.findByInspectionIdAndNotDeleted(inspectionId);
        if (defects.isEmpty()) {
            return false;
        }
        List<Long> defectIds = defects.stream().map(Defect::getId).toList();
        if (defectRevisionRepository.existsByDefectIdIn(defectIds)) {
            return true;
        }
        return defects.stream()
                .anyMatch(defect -> MANUAL_DEFECT_CONFIDENCE_SENTINEL.equals(defect.getConfidence()));
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
