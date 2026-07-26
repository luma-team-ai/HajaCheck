package com.hajacheck.bizverify.scheduler;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PENDING 재검증 결과(#888)를 회사별로 독립 커밋하는 DB 갱신 전담 — 별도 빈으로 분리해 self-invocation을
 * 회피한다(같은 클래스 내부 호출은 {@code @Transactional} 프록시가 안 걸림, {@code CompanyAccountWriter}와
 * 동일한 이유). 회사 1건마다 짧은 트랜잭션을 열어, 한 건의 갱신 실패가 나머지 건의 커밋에 영향을 주지
 * 않게 한다({@code PendingBusinessReverifyScheduler}가 건별로 호출).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingBusinessReverifyWriter {

    private final CompanyRepository companyRepository;

    @Transactional
    public void markVerified(Long companyId) {
        companyRepository.findById(companyId).ifPresentOrElse(
                Company::markBusinessVerified,
                () -> log.warn("PENDING 재검증 VERIFIED 반영 대상 회사 소멸 — companyId={}", companyId));
    }

    @Transactional
    public void markFailed(Long companyId) {
        companyRepository.findById(companyId).ifPresentOrElse(
                Company::markBusinessVerificationFailed,
                () -> log.warn("PENDING 재검증 FAILED 반영 대상 회사 소멸 — companyId={}", companyId));
    }
}
