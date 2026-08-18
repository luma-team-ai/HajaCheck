package com.hajacheck.core.analysis.service;

import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import com.hajacheck.core.analysis.dto.AnalysisStatusResponse.FileProgress;
import com.hajacheck.core.analysis.support.AnalysisFileDisplayNames;
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
import com.hajacheck.core.media.entity.MediaPurpose;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.service.AnalysisQuotaCharge;
import com.hajacheck.membership.service.QuotaService;
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

    // 고착 판정 임계값(코드 리뷰 P2, 제품 확인 완료 / 2026-08-04 QA 조사로 5분→15분 상향) —
    // ANALYZING인데 진행률 캐시(하트비트)가 이보다 오래 갱신 안 됐으면 워커가 크래시(JVM 재기동·OOM
    // 등)한 것으로 본다. 애초 5분은 PRD 목표(100장 10분, 장당 ~6초)의 정상 진행 간격만 기준으로
    // 잡았는데, 이 값이 너무 짧으면 "리퍼가 아직 살아있는 워커를 오탐으로 펜싱"하는 레이스가 생긴다
    // (reapIfStuck이 새 세대 토큰을 발급해, 진짜로 살아있던 원본 워커의 다음 DB 쓰기가 그 즉시 조용히
    // 중단된다 — InspectionAnalysisWorker#isCurrentGeneration). 사진 수가 많을수록(회차당 최대
    // 50장, MediaUploadProperties) 총 소요시간이 길어져 이 창에 노출될 확률이 커지고, FastAPI가
    // CPU 바운드 단일 워커 전제(AsyncConfig 참고)인데 Spring 쪽은 회사 간 동시 2개 잡까지 허용하므로
    // 다른 회사의 대량 분석과 겹치면 장당 대기시간이 급격히 늘어날 수 있다 — 5분은 이 경합을 버틸
    // 여유가 거의 없었다. 15분은 레이스 자체를 없애지 않는다(완화책일 뿐 — 근본 해결은 원격 워커
    // 생존을 실제로 확인하거나 남은 사진 수 기반 적응형 임계값으로 가야 하며, 이번 변경 범위 밖이다).
    private static final Duration STUCK_HEARTBEAT_THRESHOLD = Duration.ofMinutes(15);

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
    private final QuotaService quotaService;

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
     *   <li><b>기존 하자 fail-closed 가드</b>(P1 5차, 머신 검수 2차에서 소스 상태 무관으로 확장):
     *       소스 상태와 무관하게 비삭제 하자가 하나라도 있으면 {@link ErrorCode#ANALYSIS_NOT_ALLOWED}로
     *       거부한다({@link #hasExistingDefects}). 원래는 ANALYZED에만 걸었는데, createManualDefect가
     *       회차 상태를 검사하지 않아 CREATED/UPLOADING 회차에도 수동 하자가 들어갈 수 있고, 그런
     *       회차의 "첫" 분석에는 가드가 전혀 없어 사람 하자가 무조건 삭제되는 경로가 남아 있었다.
     *       "사람이 손댄 하자"를 revision/sentinel로 추론하던 방식이 그 판정을 남기지 않는 입력 경로
     *       (수동 하자 추가 등)로 계속 뚫렸기 때문에, AI/사람 구분 컬럼(#644) 도입 전까지는 소스 상태를
     *       따지지 않고 하자가 있으면 재분석 자체를 막는 fail-closed로 둔다. 이 사전 체크와 아래
     *       원자적 선점 사이의 잔여 TOCTOU는 {@link InspectionRepository#startAnalyzingIfNotRunning}의
     *       WHERE에 동일한 "비삭제 하자 없음" 조건을 함께 걸어 닫는다(사전 체크는 명확한 에러 메시지용,
     *       실제 방어선은 그 원자적 UPDATE). 분석 실행 중(ANALYZING) 자체에 새 수동 하자가 끼는 것은
     *       {@link com.hajacheck.core.defect.service.DefectRevisionService#createManualDefect}의
     *       상태 가드로 막는다.</li>
     *   <li><b>증분 분석 예외</b>(V42, #1654): 위 fail-closed 가드는 <b>ANALYZED 회차에 미분석 원본
     *       사진이 남아있는 경우</b>만 예외로 허용한다 — 분석 대상 자체를 "회차 전체"가 아니라
     *       "{@code media.analyzed_at IS NULL}인 사진"으로 좁혀서 가져오므로({@link MediaRepository
     *       #findByInspectionIdAndFileTypeAndPurposeAndAnalyzedAtIsNullOrderByIdAsc}), 하자가 있어도
     *       그 미분석 사진이 존재하면 그 사진들만 append로(기존 하자 절대 비파괴) 분석한다. 이
     *       판단({@code hasExistingDefects}, 정확히는 "증분 분석인지")은 원자적 선점({@link
     *       InspectionRepository#startAnalyzingIfNotRunning}의 {@code allowExistingDefects})과
     *       워커({@link InspectionAnalysisWorker#runAsync}의 {@code preserveExistingDefects}) 양쪽에
     *       그대로 전달돼, "이 실행은 append only"라는 결론이 세 지점 모두에서 일관된다. 미분석 사진이
     *       없는데(=이미 전량 분석 완료) 하자가 있다면 그건 "강제 전체 재분석" 시도이므로 여전히
     *       fail-closed로 거부한다.</li>
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

        // 증분 분석 대상 좁히기(V42, #1654) — "회차의 전체 원본 사진"이 아니라 "아직 AI 분석을 거치지
        // 않은 원본 사진"만 가져온다(조치 후 사진 DEFECT_ACTION은 #1641부터 이미 제외). 이 목록이 곧
        // "이번 실행이 처리할 이미지"다 — 첫 분석이든(전량 미분석이라 결과적으로 전체와 같음) 증분
        // 분석이든(ANALYZED 완료 후 새로 업로드된 사진만) 동일한 쿼리 하나로 표현된다.
        List<Media> images = mediaRepository.findByInspectionIdAndFileTypeAndPurposeAndAnalyzedAtIsNullOrderByIdAsc(
                inspectionId, MediaFileType.IMAGE, MediaPurpose.INSPECTION_SOURCE);

        boolean hasExistingDefects = hasExistingDefects(inspectionId);

        if (images.isEmpty()) {
            if (hasExistingDefects) {
                // fail-closed(코드 리뷰 P1 5차, 머신 검수 2차 계승) — 미분석 사진이 하나도 없는데 하자가
                // 있다는 건 "이미 완료된 회차를 처음부터 강제로 다시 분석"하려는 시도다(증분 대상이
                // 없으므로 증분이 아니다). 워커가 기존 하자를 소프트삭제하므로, 사람이 수동 추가·검수한
                // 하자가 무보상 유실될 수 있는 유일한 경로 — AI/사람 구분 컬럼(#644) 전까지는 계속 막는다.
                throw new BusinessException(ErrorCode.ANALYSIS_NOT_ALLOWED);
            }
            throw new BusinessException(ErrorCode.ANALYSIS_NO_MEDIA);
        }

        // 증분 분석 허용 조건(제품 결정, #1654): 하자가 있는 상태에서 재분석을 허용하는 건 오직
        // "ANALYZED 회차 + 미분석 사진 존재"뿐이다. 그 외(CREATED/UPLOADING에 이미 수동 하자가 있는
        // 상태에서의 "첫" 분석 시도)는 여전히 fail-closed — createManualDefect가 회차 상태를 검사하지
        // 않아 이런 회차에도 수동 하자가 들어갈 수 있는데, 그 첫 분석은 여전히 전체 소프트삭제 경로라
        // 사람 하자를 무보상으로 지운다.
        if (hasExistingDefects && statusBeforeAnalysis != InspectionStatus.ANALYZED) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_ALLOWED);
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

        // 월 분석 한도(plans.max_monthly_analyses) 강제 + 사용량 적립(#843). 위 회사별 동시 실행 상한
        // (PER_COMPANY_CONCURRENT_ANALYSIS_LIMIT)과는 완전히 별개의 검사다 — 저쪽은 "지금 동시에 몇 개"를
        // 막는 큐 보호이고, 이쪽은 "이번 달에 몇 장"을 막는 요금제 한도다.
        //
        // 차감을 선점(tryStartAnalyzing)보다 앞에 두는 이유: 한도 초과로 거부할 때 회차 상태를 ANALYZING 으로
        // 바꿔놨다가 되돌리는 경로를 만들지 않기 위해서다. 대신 이 메서드는 트랜잭션 밖이라 차감이 즉시
        // 커밋되므로, 이후 어떤 이유로든 실패하면 아래 catch 에서 반드시 보상 차감한다.
        // 차감이 실제로 갱신한 좌표(구독·기간·장수)를 그대로 들고 다닌다(머신 검수 P2) — 보상은 이 좌표만
        // 되돌린다. 워커는 @Async 로 수 분을 돌기 때문에, 보상 시점에 기간·구독을 재계산하면 월이 넘어갔거나
        // 요금제가 바뀐 경우 엉뚱한 행을 감산한다(AnalysisQuotaCharge javadoc 참고).
        AnalysisQuotaCharge charge = quotaService.consumeAnalysisQuota(requesterUserId, companyId, images.size());
        try {
            // hasExistingDefects를 그대로 "증분 분석" 플래그로 넘긴다(#1654) — 여기 도달했다는 건
            // 위 가드를 전부 통과했다는 뜻이라, 하자가 있다면 그건 반드시 "ANALYZED + 미분석 사진
            // 존재"인 증분 실행이다(그 외 조합은 이미 위에서 걸러짐). 원자적 선점(tryStartAnalyzing)과
            // 워커(preserveExistingDefects) 양쪽에 같은 값을 전달해 "이 실행은 append only"라는 판단을
            // 일관되게 유지한다.
            dispatchAnalysis(requesterUserId, companyId, inspectionId, images, statusBeforeAnalysis,
                    hasExistingDefects, charge);
        } catch (RuntimeException e) {
            // 선점 실패(ALREADY_RUNNING)·큐 포화(QUEUE_FULL)·예기치 못한 오류 모두 "분석이 시작되지 않음"이다 —
            // 실패한 요청이 월 한도를 갉아먹지 않도록 되돌린다.
            //
            // ⚠️ 보상 호출은 반드시 한 겹 더 감싼다(코드 리뷰 P2): refundAnalysisQuota 는 예외를 삼키지
            // 않고, @Transactional 프록시의 커밋 단계에서 UnexpectedRollbackException 이 새로 튀어나올 수도
            // 있다. 그걸 그대로 내보내면 사용자가 원래 원인(QUEUE_FULL 등) 대신 500 을 받는다 —
            // 보상 실패의 최악 결과는 이번 요청분이 월 사용량에 과대 집계되는 것뿐이므로 원인을 덮지 않는다.
            try {
                quotaService.refundAnalysisQuota(charge);
            } catch (RuntimeException refundFailure) {
                log.warn("분석 사용량 보상 차감 실패 — inspectionId={} companyId={} images={}",
                        inspectionId, companyId, images.size(), refundFailure);
            }
            throw e;
        }
    }

    /**
     * 원자적 선점 → 세대 토큰 발급 → 초기 진행률 기록 → 비동기 워커 위임. 실패 시 호출부가 사용량을 보상한다.
     * {@code charge} 는 워커까지 그대로 넘긴다 — 큐 적재에 성공하면 이후 종료 경로의 보상은 워커 책임이다.
     *
     * @param preserveExistingDefects 증분 분석 여부(#1654) — true면 원자적 선점의 "비삭제 하자 없음"
     *                                조건을 건너뛰고(tryStartAnalyzing), 워커에도 그대로 전달해 기존
     *                                하자를 소프트삭제하지 않고 append만 하도록 지시한다.
     */
    private void dispatchAnalysis(Long requesterUserId, Long companyId, Long inspectionId,
                                  List<Media> images, InspectionStatus statusBeforeAnalysis,
                                  boolean preserveExistingDefects, AnalysisQuotaCharge charge) {
        if (!inspectionService.tryStartAnalyzing(
                requesterUserId, companyId, inspectionId, ANALYSIS_ALLOWED_SOURCE_STATUSES,
                preserveExistingDefects)) {
            // 원자적 조건부 UPDATE 영향 행 0건 — 다른 요청이 먼저 선점했거나, 사전 체크 이후 허용되지
            // 않은 소스 상태(REVIEWED/REPORTED 등)로 전이됐거나(코드 리뷰 P1 10차), 증분 분석이 아닌데
            // (preserveExistingDefects=false) 사전 체크 이후 수동 하자가 새로 등록됐다(코드 리뷰 P1,
            // 머신 검수 2차) — WHERE가 허용 소스 상태와 "비삭제 하자 없음"(증분이면 생략)을 함께
            // 강제하므로 두 TOCTOU 모두에서 사람 하자가 소프트삭제로 유실되지 않는다.
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
            initialFiles.add(new FileProgress(
                    images.get(i).getId(), AnalysisFileDisplayNames.of(images.get(i), i), "waiting", null, "-"));
        }
        // unanalyzedMediaCount(#1654) — 킥오프 시점엔 이번에 큐잉한 이미지 전부가 아직 미분석이다
        // (그래서 애초에 선택됐다).
        progressStore.save(new AnalysisStatusResponse(
                inspectionId, "aiDetection", 0, images.size(), 0, initialFiles, 0, 0,
                emptyGradeMap(), 0, images.size(), Instant.now()));

        try {
            worker.runAsync(requesterUserId, companyId, inspectionId, images, statusBeforeAnalysis,
                    preserveExistingDefects, generation, charge);
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
     * 회차(소스 상태 무관)에 비삭제 하자가 하나라도 있는지 — fail-closed 재분석 가드(코드 리뷰 P1 5차,
     * 머신 검수 2차에서 ANALYZED 전용 → 소스 상태 무관으로 확장).
     *
     * <p>이전엔 "사람이 손댄 하자"를 {@code defect_revisions} 존재 → {@code confidence == 1.0} sentinel
     * 순으로 추론했는데, 판정 방식을 바꿔 막을 때마다 그 판정을 남기지 않는 입력 경로가 나타나 계속
     * 뚫렸다(라운드9~10). 대표적으로 {@code DefectRevisionService.createManualDefect}(수동 하자 추가)는
     * revision을 남기지 않아 1차 판정을 우회했고, sentinel은 근사치라 언제든 오탐/누락이 가능했다.
     * 게다가 이 메서드 호출을 ANALYZED 상태에만 걸어뒀던 탓에, createManualDefect가 회차 상태를 전혀
     * 검사하지 않는다는 점과 맞물려 CREATED/UPLOADING 회차의 "첫" 분석에는 가드 자체가 없어 수동 하자가
     * 무조건 삭제되는 경로가 남아 있었다(머신 검수 2차). AI/사람 생성을 구분하는 컬럼(#644)이 없는 한
     * 신뢰할 수 있는 선별이 불가능하므로, 그 컬럼이 들어오기 전까지는 소스 상태와 무관하게 "하자가
     * 있으면 재분석 자체를 거부"하는 fail-closed로 둔다 — 재분석 소프트삭제
     * ({@link DefectWriter#softDeleteAllForInspectionThenSave})로 인한 데이터 유실 가능성 0, 스키마 변경 0.
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
     * <p>세대 토큰도 새로 발급한다(코드 리뷰 P3, {@link #startAnalysis}의 재선점과 대칭) — 리퍼가
     * 복원하는 시점엔 아직 재선점(startAnalysis)이 일어나지 않아 이중 워커 실행 위험은 없지만,
     * 하트비트 오탐(원본 워커가 실제로는 아직 살아서 마지막 저장을 마치는 중)이면 그 워커의 다음
     * DB 쓰기가 "defect는 저장됐으나 status=UPLOADING"인 일시적 불일치를 남길 수 있다. 여기서
     * 토큰을 갈아치우면 그 잔여 쓰기도 {@link InspectionAnalysisWorker}의 세대 확인에서 즉시
     * 펜싱돼, 재선점 경로와 동일한 방식으로 창을 닫는다.
     *
     * @return 복원했으면 true, 고착이 아니어서 건너뛰었으면 false.
     */
    public boolean reapIfStuck(Long inspectionId) {
        if (!isStuck(inspectionId)) {
            return false;
        }
        inspectionService.revertStuckAnalyzing(inspectionId);
        progressStore.saveGeneration(inspectionId, java.util.UUID.randomUUID().toString());
        log.warn("ANALYZING 고착 리퍼 복원 — inspectionId={} 상태를 {}로 되돌린다", inspectionId, RECOVERY_STATUS);
        return true;
    }

    /**
     * 사용자가 명시적으로 분석을 취소한다("한 번에 하나만" 정책, 2026-07-27 팀 결정) — 분석 실행/상태
     * 화면을 이탈하려 할 때 이탈 확인창에서 "나가기"를 누르면 프론트가 호출한다.
     *
     * <p>ANALYZING이 아니면(이미 완료·실패로 종료됐거나 애초에 시작 전) 아무것도 하지 않는다(멱등) —
     * 이탈 확인창이 떠 있는 동안 분석이 자연 종료되는 레이스를 조용히 흡수한다.
     *
     * <p>세대 토큰을 새로 발급하는 것만으로 취소를 구현한다 — {@link InspectionAnalysisWorker}는 이미
     * DB 쓰기 직전마다(이미지 저장 전·최종 상태전이 전) 세대 토큰을 확인해 불일치하면 스스로 중단하고
     * {@code successCount==0}이면 월 분석 사용량도 보상한다(클래스 docstring "세대 토큰 펜싱"/"월 분석
     * 사용량 보상" 참고) — 재선점(고착 복구)이 이 메커니즘을 쓰는 것과 완전히 동일하게, "취소"도 그저
     * "이 실행은 더 이상 유효하지 않다"고 알리는 것뿐이다. 새 사용량 보상 로직을 따로 만들지 않는다.
     * 이 메서드 자체는 워커의 실제 종료를 기다리지 않고 즉시 반환한다.
     *
     * <p>상태는 고착 복구와 동일하게 {@link InspectionService#revertStuckAnalyzing}로 되돌리고
     * (ANALYZING→UPLOADING), 진행률 캐시는 지운다 — 이후 조회(getStatus)는 큐 포화 롤백과 동일하게
     * {@link #rebuildFromDb}가 "분석된 적 없음" 분기로 자연스럽게 재구성한다(새 stage 값을 만들 필요 없음).
     *
     * <p>⚠️ 순서 주의(PR 리뷰 P1) — {@link AnalysisProgressStore#delete}는 진행률 캐시뿐 아니라
     * 세대 토큰 키까지 함께 지운다({@link com.hajacheck.core.analysis.support.RedisAnalysisProgressStore#delete}/
     * {@link com.hajacheck.core.analysis.support.InMemoryAnalysisProgressStore#delete} 둘 다). 그래서
     * {@code saveGeneration}을 {@code delete}보다 먼저 호출하면, delete가 방금 발급한 새 토큰까지
     * 지워버려 {@link InspectionAnalysisWorker#isCurrentGeneration}이 "불일치(중단)"가 아니라
     * "토큰 없음(fail-soft로 계속 진행)"으로 오판한다 — 즉 취소가 워커를 전혀 펜싱하지 못하고 무시된다.
     * 반드시 {@code delete} 이후에 {@code saveGeneration}을 호출해 새 토큰이 살아남게 한다
     * ({@link #reapIfStuck}은 애초에 delete를 안 부르므로 이 문제가 없다).
     */
    public void cancelAnalysis(Long requesterUserId, Long companyId, Long inspectionId) {
        Inspection inspection = inspectionService.getOwnedInspectionEntity(requesterUserId, companyId, inspectionId);
        if (inspection.getStatus() != InspectionStatus.ANALYZING) {
            return;
        }
        inspectionService.revertStuckAnalyzing(inspectionId);
        progressStore.delete(inspectionId);
        progressStore.saveGeneration(inspectionId, java.util.UUID.randomUUID().toString());
        log.info("사용자 분석 취소 — inspectionId={} 상태를 {}로 되돌린다", inspectionId, RECOVERY_STATUS);
    }

    private AnalysisStatusResponse rebuildFromDb(Inspection inspection) {
        Long inspectionId = inspection.getId();
        // 회차 전체 미디어를 가져온다(#1654부터 analyzedAt으로 개별 분석 여부를 판정하므로, 여기서는
        // 증분 전용 필터가 아니라 기존과 동일하게 "회차의 전체 원본 촬영사진"을 그대로 쓴다 — 조치 후
        // 사진(DEFECT_ACTION)만 #1641부터 제외).
        List<Media> images = mediaRepository.findByInspectionIdAndFileTypeAndPurposeOrderByIdAsc(
                inspectionId, MediaFileType.IMAGE, MediaPurpose.INSPECTION_SOURCE);

        if (inspection.getStatus() != InspectionStatus.ANALYZED
                && inspection.getStatus() != InspectionStatus.REVIEWED
                && inspection.getStatus() != InspectionStatus.REPORTED) {
            // 분석이 끝난 적 없는 회차 — 캐시도 없으면 "아직 분석 안 됨"이 사실이다(가짜 진행률 금지).
            List<FileProgress> files = new java.util.ArrayList<>(images.size());
            for (int i = 0; i < images.size(); i++) {
                files.add(new FileProgress(
                        images.get(i).getId(), AnalysisFileDisplayNames.of(images.get(i), i), "waiting", null, "-"));
            }
            return new AnalysisStatusResponse(
                    inspectionId, "upload", 0, images.size(), 0, files, 0, 0, emptyGradeMap(), 0,
                    images.size(), Instant.now());
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

        // 개별 미디어의 "완료"/"대기" 표시는 media.analyzedAt으로 판정한다(#1654) — 예전엔 이 회차가
        // ANALYZED 이상이면 무조건 "completed"·defectCount=0으로 표시했는데, 그게 바로 이 이슈가 고친
        // 버그의 증상이다("영구 미분석(하자 0건처럼 보임)"). 분석을 아예 거치지 않은 사진은 하자
        // 0건과 구분되게 "대기"로 보여준다.
        List<FileProgress> files = new java.util.ArrayList<>(images.size());
        int unanalyzedMediaCount = 0;
        for (int i = 0; i < images.size(); i++) {
            Media media = images.get(i);
            boolean analyzed = media.getAnalyzedAt() != null;
            if (!analyzed) {
                unanalyzedMediaCount++;
            }
            Integer count = analyzed ? defectCountByMedia.getOrDefault(media.getId(), 0) : null;
            files.add(new FileProgress(media.getId(), AnalysisFileDisplayNames.of(media, i),
                    analyzed ? "completed" : "waiting", count, "-"));
        }
        int analyzedFileCount = images.size() - unanalyzedMediaCount;
        int progressPercent = images.isEmpty() ? 0 : (int) Math.round(analyzedFileCount * 100.0 / images.size());

        Map<String, Integer> gradeMap = emptyGradeMap();
        gradeCounts.forEach((grade, count) -> gradeMap.put(grade.name(), count));

        return new AnalysisStatusResponse(
                inspectionId, "done", progressPercent, images.size(), analyzedFileCount, files,
                defects.size(), riskyCrackCount, gradeMap, 0, unanalyzedMediaCount, Instant.now());
    }

    private Map<String, Integer> emptyGradeMap() {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (DefectGrade grade : DefectGrade.values()) {
            map.put(grade.name(), 0);
        }
        return map;
    }
}
