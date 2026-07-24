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
     *   <li><b>ANALYZED fail-closed 가드</b>(P1 5차): 소스 상태가 ANALYZED이고 비삭제 하자가 하나라도
     *       있으면 {@link ErrorCode#ANALYSIS_NOT_ALLOWED}로 거부한다({@link #hasExistingDefects}).
     *       "사람이 손댄 하자"를 revision/sentinel로 추론하던 방식이 그 판정을 남기지 않는 입력 경로
     *       (수동 하자 추가 등)로 계속 뚫렸기 때문에, AI/사람 구분 컬럼(#644) 도입 전까지는 하자가
     *       있으면 재분석 자체를 막는 fail-closed로 둔다(데이터 유실 가능성 0).</li>
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

        if (statusBeforeAnalysis == InspectionStatus.ANALYZED && hasExistingDefects(inspectionId)) {
            // fail-closed(코드 리뷰 P1 5차) — ANALYZED 회차에 하자가 하나라도 있으면 재분석을 거부한다.
            // 재분석은 워커가 기존 하자를 소프트삭제하므로, 사람이 수동 추가(createManualDefect)·검수한
            // 하자가 무보상 유실될 수 있는 유일한 경로다. AI/사람 생성을 구분하는 컬럼(#644)이 없는 한
            // 신뢰할 수 있는 선별이 불가능하므로, 그 전까지는 "하자가 있으면 재분석 자체를 막는다".
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_ALLOWED);
        }

        List<Media> images = mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(inspectionId, MediaFileType.IMAGE);
        if (images.isEmpty()) {
            throw new BusinessException(ErrorCode.ANALYSIS_NO_MEDIA);
        }

        // 코드 리뷰 P2 4차/10차 — 공유 실행기 큐에 넣기 전에 회사별 동시 실행 상한을 먼저 강제한다.
        // 단, "살아있는 잡"만 센다(10차): 워커 크래시로 ANALYZING에 고착된 유령 회차를 그대로 세면
        // 리퍼가 복원하기 전까지 그 회사가 영구히 상한에 걸려 분석을 못 하게 된다. 리퍼와 동일한
        // {@link #isStuck} 정의를 공유해 고착 회차를 카운트에서 제외한다. 이 카운트는 원자적이지
        // 않지만(조회 후 아래에서 별도 UPDATE) 정확한 개수 제한이 목적이 아니라 한 회사의 큐 독점을
        // 막는 최소 방어선이라 이 정도 여유는 허용한다.
        long companyAliveAnalyses = inspectionRepository
                .findByFacilityCompanyIdAndStatus(companyId, InspectionStatus.ANALYZING).stream()
                .filter(analyzing -> !isStuck(analyzing.getId()))
                .count();
        if (companyAliveAnalyses >= PER_COMPANY_CONCURRENT_ANALYSIS_LIMIT) {
            log.warn("회사별 분석 동시 실행 상한 초과 — companyId={} aliveAnalyses={} limit={}",
                    companyId, companyAliveAnalyses, PER_COMPANY_CONCURRENT_ANALYSIS_LIMIT);
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
     * ANALYZED 회차에 비삭제 하자가 하나라도 있는지 — fail-closed 재분석 가드(코드 리뷰 P1 5차).
     *
     * <p>이전엔 "사람이 손댄 하자"를 {@code defect_revisions} 존재 → {@code confidence == 1.0} sentinel
     * 순으로 추론했는데, 판정 방식을 바꿔 막을 때마다 그 판정을 남기지 않는 입력 경로가 나타나 계속
     * 뚫렸다(라운드9~10). 대표적으로 {@code DefectRevisionService.createManualDefect}(수동 하자 추가)는
     * revision을 남기지 않아 1차 판정을 우회했고, sentinel은 근사치라 언제든 오탐/누락이 가능했다.
     * AI/사람 생성을 구분하는 컬럼(#644)이 없는 한 신뢰할 수 있는 선별이 불가능하므로, 그 컬럼이
     * 들어오기 전까지는 "ANALYZED 회차에 하자가 있으면 재분석 자체를 거부"하는 fail-closed로 둔다 —
     * 재분석 소프트삭제({@link DefectWriter#softDeleteAllForInspectionThenSave})로 인한 데이터 유실
     * 가능성 0, 스키마 변경 0.
     *
     * <p>#644로 origin(AI/MANUAL) 컬럼이 도입되면 이 fail-closed를 정식 판정으로 교체한다: 소프트삭제
     * 대상을 origin=AI로 한정하고, "사람 손댐" 판정은 <b>defect_revisions + origin=MANUAL</b>로 한다.
     * ⚠️ 그때 {@code defects.is_reviewed}를 "검수 완료" 기준으로 쓰지 말 것 — is_reviewed는 등급 수정
     * 경로에서만 true가 되고 상태 확정·오탐 삭제 경로는 false로 남아 사람이 손댄 하자를 놓친다.
     * 세 편집 경로가 모두 기록되는 defect_revisions가 올바른 기준이다.
     */
    private boolean hasExistingDefects(Long inspectionId) {
        return defectRepository.existsByInspectionIdAndDeletedFalse(inspectionId);
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

    /**
     * 이 회차의 ANALYZING이 고착됐는지 — 리퍼({@link com.hajacheck.core.analysis.scheduler.StuckAnalysisReaper})와
     * 회사별 동시실행 카운트가 공유하는 "살아있는 잡" 판정(코드 리뷰 P2 10차). {@link #stuckReason}과
     * 정확히 같은 기준(Redis 진행률 하트비트, TTL 만료/장애 구분)을 쓴다 — 두 소비자가 같은 정의를
     * 공유해야 "카운트에서 제외된 회차는 리퍼가 복원하고, 복원 대상은 카운트에서 빠진다"가 일관된다.
     * inspections 테이블엔 updated_at이 없어 DB 타임스탬프가 아니라 Redis 진행률 캐시의 updatedAt을 쓴다.
     */
    public boolean isStuck(Long inspectionId) {
        return stuckReason(progressStore.find(inspectionId)) != null;
    }

    /**
     * 리퍼 전용 — ANALYZING 고착 회차를 직전 상태({@link #RECOVERY_STATUS})로 복원한다(코드 리뷰 P2 10차).
     * 고착이 아니면 아무것도 하지 않는다. 실제 상태 전이는 {@link InspectionService#revertStuckAnalyzing}가
     * 시스템 배치(사용자 컨텍스트 없음)로 수행하며, 여전히 ANALYZING일 때만 되돌린다(멱등).
     *
     * @return 복원했으면 true, 고착이 아니어서 건너뛰었으면 false.
     */
    public boolean reapIfStuck(Long inspectionId) {
        if (!isStuck(inspectionId)) {
            return false;
        }
        inspectionService.revertStuckAnalyzing(inspectionId);
        log.warn("ANALYZING 고착 리퍼 복원 — inspectionId={} 상태를 {}로 되돌린다", inspectionId, RECOVERY_STATUS);
        return true;
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
