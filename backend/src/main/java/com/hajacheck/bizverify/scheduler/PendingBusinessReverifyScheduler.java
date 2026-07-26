package com.hajacheck.bizverify.scheduler;

import com.hajacheck.auth.entity.BusinessVerificationStatus;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.bizverify.config.PendingBusinessReverifyProperties;
import com.hajacheck.bizverify.service.NtsBusinessVerifyClient;
import com.hajacheck.bizverify.service.NtsVerificationOutcome;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PENDING 사업자 진위확인 자동 재검증 배치(#888). 국세청 장애·무응답으로 fail-open된 PENDING 회사를
 * {@link NtsBusinessVerifyClient#verifyRealtime}으로 주기적으로 재조회해 VERIFIED/FAILED로 확정한다.
 *
 * <p><b>배경</b>: 가입 시 국세청이 명확한 불일치·휴업·폐업을 주면 가입을 차단하지만, 장애·무응답이면
 * fail-open으로 {@code verificationStatus=PENDING} 상태로 가입이 완료된다({@code CompanySignupService}).
 * 그런데 {@link Company#approve}는 VERIFIED가 아니면 승인을 거부하므로, 이 스케줄러 이전에는 PENDING을
 * 나중에 확정해주는 경로가 없었다 — 장애 기간 가입 회사가 영구 PENDING으로 적재된다.
 *
 * <p><b>대상</b>: {@code verificationStatus=PENDING AND businessStartDate IS NOT NULL}. 개업일자가 없는
 * 레거시 backfill 회사는 국세청 재조회 파라미터가 없어 호출해도 영원히 실패하므로 대상에서 제외한다
 * ({@link CompanyRepository#findByVerificationStatusAndBusinessStartDateIsNotNull}).
 *
 * <p><b>상태 전이</b>:
 * <ul>
 *   <li>{@link NtsVerificationOutcome#VERIFIED} → {@link PendingBusinessReverifyWriter#markVerified}</li>
 *   <li>{@link NtsVerificationOutcome#NOT_REGISTERED}/{@link NtsVerificationOutcome#MISMATCH}/
 *       {@link NtsVerificationOutcome#SUSPENDED}/{@link NtsVerificationOutcome#CLOSED} →
 *       {@link PendingBusinessReverifyWriter#markFailed}(FAILED)</li>
 *   <li>{@link NtsVerificationOutcome#SKIPPED}(국세청 장애·미설정) → 아무 갱신도 하지 않는다(PENDING
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
            log.info("PENDING 사업자 재검증 배치 스킵 — enabled=false(킬스위치), 국세청 호출 0회");
            return;
        }

        List<Company> targets = companyRepository.findByVerificationStatusAndBusinessStartDateIsNotNull(
                BusinessVerificationStatus.PENDING,
                PageRequest.of(0, properties.getMaxBatchSize(), Sort.by(Sort.Direction.ASC, "id")));

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
                        writer.markFailed(company.getId());
                        failed++;
                    }
                    case SKIPPED -> skipped++; // 국세청 장애·미설정 — PENDING 유지, 다음 회차 재시도.
                }
            } catch (Exception e) {
                // 회사 1건 실패를 격리 — 같은 회차의 나머지 회사 처리는 계속한다.
                errored++;
                log.warn("PENDING 사업자 재검증 처리 실패 — companyId={} exception={}",
                        company.getId(), e.getClass().getSimpleName());
            }
        }

        log.info("PENDING 사업자 재검증 배치 완료 — 대상 {}건, VERIFIED {}건, FAILED {}건, 스킵 {}건, 오류 {}건",
                targets.size(), verified, failed, skipped, errored);
    }
}
