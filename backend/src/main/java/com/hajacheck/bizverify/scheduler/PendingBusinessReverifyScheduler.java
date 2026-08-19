package com.hajacheck.bizverify.scheduler;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.bizverify.config.PendingBusinessReverifyProperties;
import com.hajacheck.bizverify.service.NtsBusinessVerifyClient;
import com.hajacheck.bizverify.service.NtsVerificationOutcome;
import com.hajacheck.demo.support.DemoCompanyProvenance;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 사업자 진위확인 자동 재검증 배치(#888, 대상 확장 #1324). 국세청 장애·무응답으로 fail-open된 회사를
 * {@link NtsBusinessVerifyClient#verifyRealtime}으로 주기적으로 재조회해 VERIFIED/FAILED로 확정한다.
 *
 * <p><b>배경</b>: 가입 시 국세청이 명확한 불일치·휴업·폐업을 주면 가입을 차단하지만, 장애·무응답이면
 * fail-open으로 가입이 완료된다({@code CompanySignupService}). 그런 회사를 나중에 확정해주는 경로는
 * 이 배치뿐이며, {@link Company#markBusinessVerificationFailed}(FAILED)를 찍는 <b>유일한 런타임
 * 호출부</b>다 — 즉 이 배치는 자동승인(#1324)이 열어 준 회사 스코프를 <b>사후에 되돌리는 유일한
 * 통제 수단</b>이다.
 *
 * <p><b>대상</b>({@link CompanyRepository#findNtsReverifyTargets} — 상세 조건·근거는 그 javadoc):
 * 판정 근거는 인가 플래그({@code verificationStatus})가 아니라 <b>provenance</b>
 * ({@code ocr_raw.ntsOutcome})다. #1324 자동승인이 가입 즉시 전건 VERIFIED를 찍으므로, 예전 조건
 * ({@code verificationStatus=PENDING})만 쓰면 장애 구간(SKIPPED) 가입 회사가 재검증 집합에서 영구
 * 이탈한다. 그래서 ⓐ{@code PENDING} + ⓑ{@code VERIFIED}인데 provenance로 증명 불가(SKIPPED ·
 * UNKNOWN_BACKFILL · 키 부재)를 모두 집는다. 실제 확인된 것(VERIFIED/LEGACY_VERIFIED) · REJECTED 회사 ·
 * 확정 불량(FAILED) · 개업일자 없는 레거시 backfill 회사는 제외된다.
 *
 * <p><b>상태 전이</b>:
 * <ul>
 *   <li>{@link NtsVerificationOutcome#VERIFIED} → {@link PendingBusinessReverifyWriter#markVerified}
 *       — VERIFIED 전이 + <b>provenance를 {@code ntsOutcome=VERIFIED}로 갱신</b>. 이 갱신이 다음 회차
 *       대상에서 빠지게 하는 <b>루프 종료 조건</b>이다(빠뜨리면 같은 회사를 매일 재조회한다).</li>
 *   <li>{@link NtsVerificationOutcome#NOT_REGISTERED}/{@link NtsVerificationOutcome#CLOSED} →
 *       {@link PendingBusinessReverifyWriter#markFailed}(FAILED) — FAILED 자체가 스코프 판정·DB
 *       트리거의 VERIFIED 조건을 깨뜨려 <b>전 구성원의 회사 스코프를 닫는다</b>. 멤버십 행은 의도적으로
 *       회수하지 않는다(그 javadoc 참고 — 되돌리는 경로는 #1367 의
 *       {@code Company#restoreBusinessVerificationByAdmin}).</li>
 *   <li><b>{@link NtsVerificationOutcome#MISMATCH}/{@link NtsVerificationOutcome#SUSPENDED} → 상태
 *       무변경 + 경보 로그</b>(#1367 정책 분리, 사용자 확정). 대표자 변경·계절 휴업·비영리 고유번호증은
 *       <b>사기가 아니라 정상 사업 변동</b>인데 이전 정책은 그것을 무통보 서비스 중단으로 처리했다
 *       (회사 1건이 6일간 전 API 차단된 뒤 수동 SQL 로 복구된 실사고). 자동 강등은 실재하지 않음
 *       (NOT_REGISTERED)·폐업(CLOSED)이라는 <b>확정 불량</b>으로 좁히고, 사칭 대응은 사람 판단
 *       ({@code POST /api/platform-admin/companies/{id}/verification/revoke})이 담당한다.
 *       <p>⚠️ 무변경이라 해당 회사는 <b>매 회차 계속 대상으로 잡혀 매일 경보가 반복</b>된다. 의도된
 *       것이다 — 운영이 무효화 또는 실확인으로 종결할 때까지 시끄러워야 한다. 국세청 쿼터 영향은 건당
 *       1~2회/일로 무시 가능하다(현 prod 해당 집합 ≤ 2건).</li>
 *   <li>{@link NtsVerificationOutcome#SKIPPED}(국세청 장애·미설정) → 아무 갱신도 하지 않는다(현 상태
 *       유지, 다음 회차 재시도) — 장애로 인한 SKIPPED를 FAILED로 잘못 확정하면 안 되는 것이 핵심.</li>
 * </ul>
 *
 * <p><b>데모 회사 스킵</b>(#1648): 데모 시더가 만든 회사는 BRN 이 실존 불가 값
 * ({@code DemoSeedService#DEMO_BUSINESS_NUMBER})이라 국세청에 조회하면 항상 확정 불량으로 응답돼
 * {@link PendingBusinessReverifyWriter#markFailed}(FAILED)로 강등되고, FAILED 는 재검증 대상에서 영구
 * 제외돼 회사 스코프가 다시 열리지 않는다(prod 데모 계정 전 API 403 재발). 국세청 호출 <b>전에</b>
 * {@link DemoCompanyProvenance#isDemoSeeded}(BRN+provenance 이중 판정, {@code DemoResetService}와 공유)로
 * 걸러 국세청을 아예 호출하지 않는다 — {@code findNtsReverifyTargets} SQL 자체는 건드리지 않는다(데모
 * 하드코딩을 쿼리에 넣지 않는다).
 *
 * <p><b>대기열 공정성</b>(#1367 P1-C): 상태를 바꾸지 않는 판정(MISMATCH·SUSPENDED·SKIPPED·데모 스킵)은
 * 회사를 대상 집합에 <b>영구 거주</b>시킨다. 옛 정렬({@code id asc})은 id 가 작은 앞 n 건만 반복
 * 처리했으므로 영구 거주자가 회차 상한을 채우면 신규 가입 회사가 <b>영원히 재검증되지 않는</b> 무증상
 * fail-open 이 성립했다. 그래서 처리한 회사마다 {@code ntsLastAttemptAt} 을 스탬프하고
 * ({@link PendingBusinessReverifyWriter#stampAttempt}) 대상 조회가 그 값 오름차순으로 정렬해 순환시킨다
 * ({@link CompanyRepository#findNtsReverifyTargets} 정렬 javadoc). 상한을 꽉 채운 회차는 별도
 * {@code log.error} 로 표면화한다.
 *
 * <p><b>총량 통제</b>: {@link PendingBusinessReverifyProperties#isEnabled()} 킬스위치 +
 * {@link PendingBusinessReverifyProperties#getMaxBatchSize()} 회차당 상한(설정값, Properties Javadoc
 * 참고). 이 배치는 {@code BusinessVerificationService}의 공개 API rate-limit을 타지 않고(공개 컨트롤러를
 * 거치지 않는 배치 코드 경로) 클라이언트를 직접 호출하므로, 배치 상한이 국세청 서비스키 일일 쿼터를
 * 지키는 유일한 통제 수단이다.
 *
 * <p><b>트랜잭션·장애 격리</b>: 국세청 호출({@code verifyRealtime})은 이 메서드(트랜잭션 없음)에서
 * 수행하고, DB 갱신은 {@link PendingBusinessReverifyWriter}(별도 @Transactional 빈, 회사별 독립 커밋)에
 * 위임한다 — {@code CompanySignupService}가 외부 호출을 트랜잭션 밖에 두는 것과 동일한 이유(느린 외부
 * 호출이 DB 커넥션/트랜잭션을 점유하지 않게 한다). 회사 1건 처리 실패(네트워크 예외 포함)를 격리해
 * 나머지 회사 처리를 막지 않는다.
 *
 * <p><b>개인정보 로깅 금지</b>: 사업자등록번호·대표자명·개업일자는 로그에 남기지 않는다. 회차 요약
 * (대상 N건 / VERIFIED n / FAILED n / 경보 n / 스킵 n / 오류 n)과 결과 코드, 그리고 <b>companyId 목록</b>
 * (식별자는 개인정보가 아니다)만 기록한다({@code InspectionDueNotificationScheduler} 완료 로그 형식 참고).
 *
 * <p><b>경보 축</b>(#1367): 강등·경보가 1건이라도 있으면 회차 요약을 {@code info} 가 아니라 {@code warn}
 * 으로 남기고 해당 {@code companyId} 목록을 함께 적는다. 이전에는 요약이 건수만 담은 {@code info} 라
 * <b>어떤 회사가 강등됐는지 사후에 알아낼 방법이 전혀 없었다</b>(prod 로그 보존 한계와 겹쳐 실사고
 * 원인 규명이 불가능했다).
 *
 * <p>⚠️ <b>단일 인스턴스 실행 전제</b>: 이 배치는 read-then-write로 대상을 조회·갱신한다. 다중 인스턴스
 * 스케일아웃 시 같은 회사가 겹쳐 조회될 수 있으나, 국세청 재호출 자체는 멱등(같은 결과를 다시 확정)이라
 * {@code InspectionDueNotificationScheduler}의 알림 중복 발행 같은 유해한 중복은 아니다 — 다만 국세청
 * 호출 총량이 인스턴스 수만큼 배가돼 일일 쿼터 산정이 어긋난다. 스케일아웃 시점에는 ShedLock 같은 분산
 * 락 도입을 검토할 것.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingBusinessReverifyScheduler {

    private final CompanyRepository companyRepository;
    private final NtsBusinessVerifyClient ntsBusinessVerifyClient;
    private final PendingBusinessReverifyWriter writer;
    private final PendingBusinessReverifyProperties properties;

    @Scheduled(cron = "${biz-verify.pending-reverify.cron:0 30 5 * * *}", zone = "Asia/Seoul")
    public void reverifyPendingCompanies() {
        if (!properties.isEnabled()) {
            log.info("사업자 재검증 배치 스킵 — enabled=false(킬스위치), 국세청 호출 0회");
            return;
        }

        // 정렬은 네이티브 쿼리의 order by 로 고정 — Pageable 에 Sort 를 싣지 않는다(리포지토리 javadoc).
        List<Company> targets = companyRepository.findNtsReverifyTargets(
                PageRequest.of(0, properties.getMaxBatchSize()));

        int verified = 0;
        int skipped = 0;
        int errored = 0;
        int demoSkipped = 0;
        // 강등·경보 대상은 건수뿐 아니라 companyId 까지 남긴다 — 요약 로그만으로 사후 추적이 되게 한다.
        List<Long> failedCompanyIds = new ArrayList<>();
        List<Long> alertedCompanyIds = new ArrayList<>();
        for (Company company : targets) {
            if (DemoCompanyProvenance.isDemoSeeded(company)) {
                // 데모 회사는 국세청에 실재하지 않는 BRN 이라 호출하면 항상 확정 불량(FAILED)으로
                // 강등되어 회사 스코프가 영구 차단된다(#1648) — 국세청 호출 전에 스킵한다.
                demoSkipped++;
                // 데모 회사는 provenance 에 ntsOutcome 키가 없어 대상 집합의 "영구 거주자"다 — 스탬프를
                // 찍지 않으면 id 가 작은 데모 회사가 매 회차 대기열 앞자리를 고정 점유한다(#1367 P1-C).
                stampAttemptQuietly(company.getId());
                log.debug("사업자 재검증 스킵 — 데모 시드 회사 (companyId={})", company.getId());
                continue;
            }
            try {
                NtsVerificationOutcome outcome = ntsBusinessVerifyClient.verifyRealtime(
                        company.getBusinessRegistrationNumber(),
                        company.getRepresentativeName(),
                        company.getBusinessStartDate());
                switch (outcome) {
                    case VERIFIED -> {
                        writer.markVerified(company.getId());
                        verified++;
                    }
                    case NOT_REGISTERED, CLOSED -> {
                        // 확정 불량(실재하지 않음·폐업)만 자동 강등한다(#1367 정책 분리).
                        // 판정 outcome 을 함께 넘긴다 — writer 가 경고 로그에 사유를 남겨야 운영이
                        // 확정 불량(회사 스코프 차단)의 근거를 사후에 추적할 수 있다.
                        writer.markFailed(company.getId(), outcome);
                        failedCompanyIds.add(company.getId());
                    }
                    case MISMATCH, SUSPENDED -> {
                        // #1367 — 정상 사업 변동(대표자 변경·계절 휴업·비영리 고유번호증)일 수 있어
                        // 자동 강등하지 않는다. 상태 무변경이라 다음 회차에도 같은 회사가 다시 잡혀
                        // 운영이 종결할 때까지 이 경보가 매일 반복된다(의도된 동작).
                        // 판정 자체는 DB 에도 남긴다 — 로그에만 두면 진단 API 로 도달하지 못한다(P2-3).
                        writer.stampAlert(company.getId(), outcome);
                        alertedCompanyIds.add(company.getId());
                        log.warn("사업자 재검증 경보 — 자동 강등하지 않음(#1367): 정상 사업 변동일 수 있다."
                                        + " 운영이 사칭으로 판단하면 POST /api/platform-admin/companies/{}"
                                        + "/verification/revoke 로 무효화하고, 실물 확인으로 정상이면"
                                        + " .../verification/override 로 개방한다. 종결 전까지 이 경보는"
                                        + " 매 회차 반복된다. companyId={}, outcome={}",
                                company.getId(), company.getId(), outcome);
                    }
                    case SKIPPED -> {
                        skipped++; // 국세청 장애·미설정 — 현 상태 유지, 다음 회차 재시도.
                        stampAttemptQuietly(company.getId());
                    }
                }
            } catch (Exception e) {
                // 회사 1건 실패를 격리 — 같은 회차의 나머지 회사 처리는 계속한다.
                errored++;
                stampAttemptQuietly(company.getId());
                log.warn("사업자 재검증 처리 실패 — companyId={} exception={}",
                        company.getId(), e.getClass().getSimpleName());
            }
        }

        // 강등·경보가 있으면 warn 으로 올리고 companyId 목록을 함께 남긴다(#1367) — 건수만 남은 info
        // 로그로는 "어떤 회사가 왜 막혔는지"를 사후에 재구성할 수 없다.
        String summary = "사업자 재검증 배치 완료 — 대상 {}건, VERIFIED {}건, FAILED {}건(companyIds={}), "
                + "경보 {}건(companyIds={}), 스킵 {}건, 데모스킵 {}건, 오류 {}건";
        Object[] args = {targets.size(), verified,
                failedCompanyIds.size(), failedCompanyIds,
                alertedCompanyIds.size(), alertedCompanyIds,
                skipped, demoSkipped, errored};
        if (!failedCompanyIds.isEmpty() || !alertedCompanyIds.isEmpty()) {
            log.warn(summary, args);
        } else {
            log.info(summary, args);
        }

        // 대기열 포화 표면화(#1367 P1-C) — 상한을 꽉 채웠다는 것은 뒤쪽(주로 신규 가입) 회사가 이번
        // 회차에 아예 조회되지 않았다는 뜻이다. 라운드로빈 정렬로 굶주림은 막았지만, 하루 1회 배치에서
        // 대상이 상한을 넘으면 각 회사의 재검증 주기가 그만큼 길어진다 — 자동 통제가 조용히 느려지는
        // 상태라 반드시 사람에게 보여야 한다.
        if (targets.size() >= properties.getMaxBatchSize()) {
            log.error("재검증 대기열 포화 — 대상 {}건이 회차 상한 {}건을 채웠다. 신규 가입 회사의 재검증이"
                            + " 지연되거나 누락될 수 있다(확정 불량 자동 강등이 늦어진다)."
                            + " 경보 회사 종결(revoke/override) 또는 상한 상향을 검토할 것.",
                    targets.size(), properties.getMaxBatchSize());
        }
    }

    /**
     * 처리 시도 스탬프 — 실패해도 회차를 중단시키지 않는다(#1367 P1-C).
     * 스탬프는 정렬 축일 뿐 통제 자체가 아니므로, 여기서 예외가 새어 나가면 오히려 나머지 회사 처리를
     * 막아 손해가 크다. 예외 경로에서도 호출되므로 반드시 조용히 삼킨다.
     */
    private void stampAttemptQuietly(Long companyId) {
        try {
            writer.stampAttempt(companyId);
        } catch (Exception e) {
            log.warn("사업자 재검증 시도 스탬프 실패(무시하고 진행) — companyId={} exception={}",
                    companyId, e.getClass().getSimpleName());
        }
    }
}
