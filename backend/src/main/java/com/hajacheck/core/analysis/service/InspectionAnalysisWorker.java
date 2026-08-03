package com.hajacheck.core.analysis.service;

import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.core.ai.dto.DetectedDefectItem;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import com.hajacheck.core.analysis.dto.AnalysisStatusResponse.FileProgress;
import com.hajacheck.core.analysis.support.AnalysisFileDisplayNames;
import com.hajacheck.core.analysis.support.AnalysisProgressStore;
import com.hajacheck.core.analysis.support.InspectionAnalysisNotificationPayload;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.service.DefectWriter;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.global.config.AsyncConfig;
import com.hajacheck.membership.service.AnalysisQuotaCharge;
import com.hajacheck.membership.service.QuotaService;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.service.NotificationService;
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
 *
 * <p>재분석 시 기존 하자 소프트삭제(멱등화)는 {@link InspectionAnalysisService}가 아니라 이 클래스가
 * 담당한다(코드 리뷰 P1/P2 픽스) — 실제로 첫 탐지가 성공한 시점에 지연 실행해, 트리거 메서드에서
 * 미리 지웠다가 이후 큐 포화·전체 실패로 롤백되며 검수 완료된 하자가 보상 없이 유실되는 걸 막는다.
 * 그 삭제는 첫 이미지의 저장과 {@link DefectWriter#softDeleteAllForInspectionThenSave} 한 트랜잭션으로
 * 원자화돼 있다 — saveAll만 따로 뒤에 두면 실패 시 삭제가 보상 안 되고, 반대로 저장부터 하면 방금
 * 저장한 새 하자까지 소프트삭제(전체 비삭제 행 대상)에 휩쓸려 지워지는 두 오답을 모두 피한다.
 *
 * <p><b>세대 토큰 펜싱</b>(코드 리뷰 P1) — {@link InspectionAnalysisService}의 ANALYZING 고착 복구는
 * 하트비트만으로 판단하므로 오탐 가능성이 있다(이 워커가 실제로는 아직 살아서 도는 중인데, 진행률
 * 캐시 갱신이 지연되거나 분석 실행기 큐가 밀려 첫 이미지 처리 자체가 늦게 시작된 경우 등). 오탐이면
 * 원본 워커가 여전히 도는 채로 재선점된 새 워커가 하나 더 뜨는데, 아무 방지 장치가 없으면 두 워커가
 * 같은 회차에 동시에 defect를 쓰면서(append 중복·유령 행) 데이터가 손상된다. 이를 막기 위해 이
 * 워커는 DB에 쓰기 직전마다({@link #isCurrentGeneration}) 자신이 받은 세대 토큰이 여전히
 * {@link AnalysisProgressStore}상 "현재" 토큰인지 확인하고, 다르면(다른 실행이 재선점해 새 토큰을
 * 발급함 = 자신이 추월당함) 조용히 중단한다(더 이상 쓰기·상태전이 없이 return) — 예외를 던지지 않는
 * 이유는 이게 오류가 아니라 "정상적으로 다른 실행에 자리를 내준 것"이기 때문이다.
 *
 * <p><b>월 분석 사용량 보상</b>(#843, 코드 리뷰 P1) — 사용량은 {@link InspectionAnalysisService}가
 * 큐 적재 <i>전에</i> 차감하고, 큐 적재까지 성공하면 그쪽 catch 는 더 이상 도달하지 않는다. 따라서
 * "적재는 됐지만 아무 결과도 남기지 못한" 종료 경로의 보상은 <b>이 워커의 책임</b>이다. 기준은
 * {@code successCount == 0}, 즉 <b>DB에 한 건도 커밋하지 못한 실행만</b> 되돌린다:
 * <ul>
 *   <li><b>전량 실패</b>(AI 서버 다운 등) → 보상. 상태도 시작 전으로 되돌아가 재시도 가능하므로,
 *       보상하지 않으면 재시도할 때마다 한도만 깎여 FREE(월 50장)는 금세 소진되고 복구 API도 없다.</li>
 *   <li><b>추월당함</b>(세대 토큰 불일치) → {@code successCount == 0} 일 때만 보상. 이 실행은 결과를
 *       남기지 못하고 끝나고, 자리를 넘겨받은 실행은 자기 몫을 따로 차감했기 때문이다.</li>
 *   <li><b>부분 실패</b>({@code successCount > 0}) → 보상하지 않는다. 성공한 장수만큼 결과가 남았고,
 *       장수 단위 부분 환불은 "요청 1건 = 이미지 N장" 차감 단위와 어긋난다.</li>
 * </ul>
 *
 * <p><b>⚠️ 알려진 과대 집계 한 가지</b>(#843 머신 검수 P3-2, 실제 코드로 확인함) — "추월당함 +
 * {@code successCount > 0}" 조합의 근거를 예전엔 "이미 저장한 하자는 남으므로 사용량도 실제 소비된 것"
 * 이라고 적었는데, <b>그 서술은 한 경로에서 사실이 아니다</b>:
 * <ul>
 *   <li><b>리퍼가 토큰을 올린 경우</b>({@link com.hajacheck.core.analysis.scheduler.StuckAnalysisReaper}
 *       → {@code reapIfStuck}) — 새 실행이 뜨지 않으므로 이 워커가 저장한 하자는 그대로 남는다.
 *       기존 근거가 그대로 성립한다.</li>
 *   <li><b>다른 요청이 재선점한 경우</b> — 넘겨받은 실행 B가 첫 탐지에 성공하는 순간
 *       {@link DefectWriter#softDeleteAllForInspectionThenSave} 가 그 회차의 <b>비삭제 하자 전체</b>를
 *       소프트삭제한다({@code findByInspectionIdAndNotDeleted} 대상 = A가 저장한 것 포함). 즉 A의 부분
 *       결과는 실제로 사라지고, A와 B가 각각 N장을 차감해 사용자가 2N을 부담한다.</li>
 * </ul>
 * 다만 이 경로는 매우 좁다 — {@link InspectionAnalysisService#startAnalysis} 의 fail-closed 가드와
 * {@code InspectionRepository#startAnalyzingIfNotRunning} 의 WHERE 가 둘 다 "비삭제 하자 없음"을
 * 요구하므로, A가 하자를 <b>커밋한 뒤</b>에는 B가 애초에 선점하지 못한다. 도달하려면 B의 선점이 A의
 * 세대 토큰 확인과 첫 커밋 사이(READ COMMITTED에서 A의 INSERT가 아직 안 보이는 구간)에 끼어야 한다.
 * <b>이 좁은 트리거에서는 과대 집계를 감수한다</b> — 여기서 보상하면 반대로 "리퍼 경로에서 결과가 남았는데
 * 환불"이 되어 무료 분석 경로가 열리고, 두 경로를 코드로 구분할 수단이 지금은 없다. 근본 해소는 AI/사람
 * 생성 구분 컬럼(#644)으로 소프트삭제 대상을 좁힌 뒤 후속 이슈에서 다룬다(동작은 현행 유지).
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
    private final QuotaService quotaService;
    private final NotificationService notificationService;

    /**
     * @param generation 이 실행이 선점될 때 발급된 세대 토큰(코드 리뷰 P1, 클래스 javadoc "세대 토큰
     *                   펜싱" 참고) — DB 쓰기 직전마다 {@link #isCurrentGeneration}으로 유효성을 재확인한다.
     * @param charge {@link InspectionAnalysisService}가 큐 적재 전에 차감한 월 분석 사용량의 좌표
     *               (구독·기간·장수). 이 워커가 아무 결과도 남기지 못하고 끝나면 <b>이 좌표만</b> 되돌린다 —
     *               @Async 로 수 분이 흐르는 동안 월이 넘어가거나 요금제가 바뀌어도 "깎았던 그 행"이
     *               정확히 복구되도록, 보상 시점에 기간·구독을 재계산하지 않는다.
     */
    @Async(AsyncConfig.ANALYSIS_TASK_EXECUTOR)
    public void runAsync(Long requesterUserId, Long companyId, Long inspectionId, List<Media> images,
                          String generation, AnalysisQuotaCharge charge) {
        List<FileProgress> files = new ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            files.add(new FileProgress(
                    images.get(i).getId(), AnalysisFileDisplayNames.of(images.get(i), i), "waiting", null, "-"));
        }

        int detectedDefectCount = 0;
        int riskyCrackCount = 0;
        int failedCount = 0;
        int successCount = 0;
        // 재분석 멱등화(코드 리뷰 P2) — 기존 하자 소프트삭제를 이 루프 안에서, 실제로 첫 탐지가
        // 성공한 시점에 딱 한 번만 지연 실행한다. InspectionAnalysisService가 미리 지워버리면
        // 이후 이미지 전체 실패로 롤백될 때 검수 완료된 회차의 기존 하자가 보상 없이 영구 유실된다
        // (이 필드가 false로 남으면 = 이번 실행이 아무 결실도 못 맺었으면 = 기존 데이터를 건드리지 않았다는 뜻).
        boolean oldDefectsCleared = false;
        Map<DefectGrade, Integer> gradeCounts = new EnumMap<>(DefectGrade.class);

        for (int i = 0; i < images.size(); i++) {
            Media media = images.get(i);
            files.set(i, new FileProgress(media.getId(), AnalysisFileDisplayNames.of(media, i), "analyzing", null, "처리중..."));
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
                // 코드 리뷰 P1 — DB에 쓰기 직전 세대 토큰을 재확인한다(클래스 javadoc "세대 토큰
                // 펜싱" 참고). 다른 실행이 재선점해 자신이 추월당했으면 이 이미지의 결과를 버리고
                // 즉시 중단한다 — 여기서 계속 쓰면 재선점된 새 워커의 결과와 뒤섞여 손상된다.
                if (!isCurrentGeneration(inspectionId, generation)) {
                    log.warn("분석 세대 토큰 불일치(추월당함) — inspectionId={} mediaId={} 쓰기를 건너뛰고 중단한다",
                            inspectionId, media.getId());
                    refundIfNothingPersisted(inspectionId, charge, successCount);
                    return;
                }

                // 코드 리뷰 P2(잔여 창) — 소프트삭제와 "첫" 저장을 DefectWriter 쪽 한 트랜잭션으로
                // 묶는다(원자화). saveAll만 따로 뒤에 두면 그 사이 예외가 났을 때 이미 커밋된 삭제가
                // 보상되지 않고, 반대로 저장부터 하고 삭제하면 방금 저장한 새 하자까지
                // softDeleteAllForInspection(전체 비삭제 행 대상)에 휩쓸려 지워진다 — 둘 다 오답이라
                // 같은 트랜잭션으로만 안전하다.
                if (!oldDefectsCleared) {
                    defectWriter.softDeleteAllForInspectionThenSave(requesterUserId, inspectionId, toSave);
                    oldDefectsCleared = true;
                } else {
                    defectWriter.saveAll(toSave);
                }
                detectedDefectCount += toSave.size();
                successCount++;

                String elapsed = formatElapsed(startedAt);
                files.set(i, new FileProgress(
                        media.getId(), AnalysisFileDisplayNames.of(media, i), "completed", toSave.size(), elapsed));
            } catch (Exception e) {
                // 이미지 1장 실패를 격리 — 회차 전체를 중단하지 않는다.
                failedCount++;
                log.warn("AI 분석 실패 — inspectionId={} mediaId={} exception={}",
                        inspectionId, media.getId(), e.getClass().getSimpleName());
                files.set(i, new FileProgress(media.getId(), AnalysisFileDisplayNames.of(media, i), "failed", null, "오류"));
            }

            publish(inspectionId, images.size(), i + 1, files, detectedDefectCount, riskyCrackCount,
                    gradeCounts, failedCount);
        }

        // 사진 하나라도 분석 실패면 FAILED, 전부 성공이면 ANALYZED — 회차에 속한 사진들의 분석이
        // 전부 끝난 시점(이 지점)에 한 번에 집계해서 결정한다. 개별 사진이 실패로 떨어질 때마다
        // 바로 판정하지 않는 이유는 아직 분석 중인 다른 사진이 남아있을 수 있어서다.
        // stage를 "aiDetection"이 아니라 "failed"로 명시한다(코드 리뷰 P2, 프론트 폴링 종료 신호) —
        // "aiDetection"으로 두면 useAnalysisStatus가 stage==='done'만 폴링 중단 조건으로 보므로
        // 실패한 잡을 영원히 "진행 중 0%"로 오인해 폴링을 멈추지 않는다.
        // 코드 리뷰 P1 — 상태전이(advanceStatus) 직전에도 세대 토큰을 한 번 더 확인한다. 마지막
        // 이미지의 쓰기 시점 확인을 통과한 뒤에도, 그 이후(집계 등 순수 연산만 남은 짧은 구간)
        // 추월당했을 가능성을 마저 좁힌다.
        if (!isCurrentGeneration(inspectionId, generation)) {
            log.warn("분석 세대 토큰 불일치(추월당함) — inspectionId={} 상태전이를 건너뛰고 중단한다", inspectionId);
            refundIfNothingPersisted(inspectionId, charge, successCount);
            return;
        }

        if (failedCount > 0) {
            log.warn("AI 분석 실패 사진 발생 — inspectionId={} totalImages={} failedCount={} successCount={} — FAILED로 전이한다",
                    inspectionId, images.size(), failedCount, successCount);
            // 코드 리뷰 P1(#843) — 상태를 FAILED로 바꾸고도 월 분석 사용량을 그대로 소진시키면,
            // 결과를 하나도 못 남긴 채(successCount==0) 재시도할 때마다 한도만 깎인다(클래스 javadoc
            // "월 분석 사용량 보상" 참고). successCount>0(부분 실패)이면 결과가 남았으므로 보상하지 않는다
            // (refundIfNothingPersisted 내부 분기).
            //
            // 재검토 P3 — advanceStatus 보다 **먼저** 보상한다. advanceStatus 는 내부적으로
            // CompanyScopeGuard 재검증을 타므로, 분석이 도는 동안 요청자의 회사 소속이 해제되면 여기서
            // 예외가 난다. 보상이 뒤에 있으면 그 좁은 창에서 사용량만 소진된 채 복구 수단이 없다(관리자
            // 보정 API 없음). 순서를 뒤집으면 최악이라도 "상태가 ANALYZING 으로 잔류"인데 그건
            // StuckAnalysisReaper 가 복구한다.
            refundIfNothingPersisted(inspectionId, charge, successCount);
            inspectionService.advanceStatus(requesterUserId, companyId, inspectionId, InspectionStatus.FAILED);
            AnalysisStatusResponse failedProgress = new AnalysisStatusResponse(
                    inspectionId, "failed", 100, images.size(), images.size(), files,
                    detectedDefectCount, riskyCrackCount, toGradeCountMap(gradeCounts), failedCount,
                    Instant.now());
            progressStore.save(failedProgress);
            return;
        }

        Inspection inspection =
                inspectionService.advanceStatus(requesterUserId, companyId, inspectionId, InspectionStatus.ANALYZED);
        notifyAnalysisDone(inspection);
        AnalysisStatusResponse done = new AnalysisStatusResponse(
                inspectionId, "done", 100, images.size(), images.size(), files,
                detectedDefectCount, riskyCrackCount, toGradeCountMap(gradeCounts), failedCount, Instant.now());
        progressStore.save(done);
    }

    /**
     * ANALYZED 전이 직후 알림 2건을 발행한다(#494/#495) — 같은 전이 지점에서 수신자만 다르게 함께
     * 발행하기로 한 팀 합의(이슈 코멘트 참고): {@code ANALYSIS_DONE}은 "요청한 분석이 끝났다"는 완료
     * 알림으로 회차를 만든 사람({@code createdBy})에게, {@code REVIEW_PENDING}은 "확인이 필요하다"는
     * 액션 알림으로 담당 점검자({@code assignedInspectorId})에게 보낸다. 1인이 두 역할을 겸하면 같은
     * 사람이 두 알림을 모두 받는데, 이는 의도된 동작이다(별도 dedupe 없음).
     *
     * <p>알림 발행 실패는 절대 이 메서드 밖으로 전파시키지 않는다 — {@link
     * #refundIfNothingPersisted}와 동일한 이유로, 예외가 새면 바로 다음 줄의 진행률 저장("done")이
     * 건너뛰어져 프론트가 영원히 폴링한다. 분석 자체는 이미 완전히 끝난 상태라 알림 유실이 최악의
     * 결과다(사용자가 알림센터 대신 화면을 새로고침해 결과를 확인할 수 있음).
     */
    private void notifyAnalysisDone(Inspection inspection) {
        try {
            String payload = InspectionAnalysisNotificationPayload.serialize(
                    inspection.getId(), inspection.getRoundNo());
            notificationService.notify(inspection.getCreatedBy(), NotificationType.ANALYSIS_DONE, payload);
            notificationService.notify(inspection.getAssignedInspectorId(), NotificationType.REVIEW_PENDING, payload);
        } catch (RuntimeException e) {
            log.warn("분석 완료 알림 발행 실패 — inspectionId={} exception={}",
                    inspection.getId(), e.getClass().getSimpleName());
        }
    }

    /**
     * 이 실행이 DB에 아무것도 커밋하지 못했으면 월 분석 사용량을 되돌린다(#843, 클래스 javadoc
     * "월 분석 사용량 보상" 참고). {@code successCount > 0} 이면 결과가 남았으므로 보상하지 않는다.
     *
     * <p>보상 실패는 절대 여기서 전파시키지 않는다 — 이 워커는 {@code @Async}라 예외를 받아줄 호출부가
     * 없고, 보상 실패 때문에 뒤따르는 진행률 저장(프론트 폴링 종료 신호)까지 건너뛰면 화면이 "진행 중"에
     * 영원히 갇힌다. 최악의 결과는 이번 요청분이 월 사용량에 과대 집계되는 것뿐이다.
     */
    private void refundIfNothingPersisted(Long inspectionId, AnalysisQuotaCharge charge, int successCount) {
        if (successCount > 0) {
            return;
        }
        try {
            quotaService.refundAnalysisQuota(charge);
        } catch (RuntimeException e) {
            log.warn("분석 사용량 보상 차감 실패 — inspectionId={} charge={}", inspectionId, charge, e);
        }
    }

    /**
     * 세대 토큰이 여전히 유효한지 확인한다(코드 리뷰 P1, 클래스 javadoc "세대 토큰 펜싱" 참고).
     * 저장소가 비어있거나 장애로 못 읽으면(fail-soft) 펜싱 정보가 없는 것으로 보아 보수적으로
     * "유효함(계속 진행)"으로 판단한다 — 저장소 장애가 정상 진행 중인 분석 잡까지 막아버리면 안 된다.
     */
    private boolean isCurrentGeneration(Long inspectionId, String generation) {
        return progressStore.findGeneration(inspectionId)
                .map(generation::equals)
                .orElse(true);
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
                .areaRatio(item.areaRatio())
                .build();
    }

    private void publish(Long inspectionId, int total, int analyzedCount, List<FileProgress> files,
                          int detectedDefectCount, int riskyCrackCount, Map<DefectGrade, Integer> gradeCounts,
                          int failedCount) {
        int percent = total == 0 ? 0 : (int) Math.round(analyzedCount * 100.0 / total);
        AnalysisStatusResponse progress = new AnalysisStatusResponse(
                inspectionId, "aiDetection", percent, total, analyzedCount, List.copyOf(files),
                detectedDefectCount, riskyCrackCount, toGradeCountMap(gradeCounts), failedCount, Instant.now());
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

    private String formatElapsed(Instant startedAt) {
        long millis = Duration.between(startedAt, Instant.now()).toMillis();
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }
}
