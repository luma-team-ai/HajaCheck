package com.hajacheck.bizverify.scheduler;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.bizverify.config.PendingBusinessReverifyProperties;
import com.hajacheck.bizverify.service.NtsBusinessVerifyClient;
import com.hajacheck.bizverify.service.NtsVerificationOutcome;
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
 *   <li>{@link NtsVerificationOutcome#NOT_REGISTERED}/{@link NtsVerificationOutcome#MISMATCH}/
 *       {@link NtsVerificationOutcome#SUSPENDED}/{@link NtsVerificationOutcome#CLOSED} →
 *       {@link PendingBusinessReverifyWriter#markFailed}(FAILED) — FAILED 자체가 스코프 판정·DB
 *       트리거의 VERIFIED 조건을 깨뜨려 <b>전 구성원의 회사 스코프를 닫는다</b>. 멤버십 행은 의도적으로
 *       회수하지 않는다(비가역·복구 경로 부재 — 그 javadoc 참고, 후속 #1367)</li>
 *   <li>{@link NtsVerificationOutcome#SKIPPED}(국세청 장애·미설정) → 아무 갱신도 하지 않는다(현 상태
 *       유지, 다음 회차 재시도) — 장애로 인한 SKIPPED를 FAILED로 잘못 확정하면 안 되는 것이 핵심.</li>
 * </ul>
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
 * (대상 N건 / VERIFIED n / FAILED n / 스킵 n / 오류 n)과 결과 코드만 기록한다
 * ({@code InspectionDueNotificationScheduler} 완료 로그 형식 참고).
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
        int failed = 0;
        int skipped = 0;
        int errored = 0;
        for (Company company : targets) {
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
                    case NOT_REGISTERED, MISMATCH, SUSPENDED, CLOSED -> {
                        // 판정 outcome 을 함께 넘긴다 — writer 가 경고 로그에 사유를 남겨야 운영이
                        // 확정 불량(회사 스코프 차단)의 근거를 사후에 추적할 수 있다.
                        writer.markFailed(company.getId(), outcome);
                        failed++;
                    }
                    case SKIPPED -> skipped++; // 국세청 장애·미설정 — 현 상태 유지, 다음 회차 재시도.
                }
            } catch (Exception e) {
                // 회사 1건 실패를 격리 — 같은 회차의 나머지 회사 처리는 계속한다.
                errored++;
                log.warn("사업자 재검증 처리 실패 — companyId={} exception={}",
                        company.getId(), e.getClass().getSimpleName());
            }
        }

        log.info("사업자 재검증 배치 완료 — 대상 {}건, VERIFIED {}건, FAILED {}건, 스킵 {}건, 오류 {}건",
                targets.size(), verified, failed, skipped, errored);
    }
}
