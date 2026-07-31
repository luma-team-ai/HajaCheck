package com.hajacheck.bizverify.scheduler;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.CompanyMembershipStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사업자 진위확인 재검증 결과(#888)를 회사별로 독립 커밋하는 DB 갱신 전담 — 별도 빈으로 분리해
 * self-invocation을 회피한다(같은 클래스 내부 호출은 {@code @Transactional} 프록시가 안 걸림,
 * {@code CompanyAccountWriter}와 동일한 이유). 회사 1건마다 짧은 트랜잭션을 열어, 한 건의 갱신 실패가
 * 나머지 건의 커밋에 영향을 주지 않게 한다({@code PendingBusinessReverifyScheduler}가 건별로 호출).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingBusinessReverifyWriter {

    private final CompanyRepository companyRepository;
    private final CompanyMembershipRepository companyMembershipRepository;

    /**
     * 국세청이 진위를 확인해 준 회사를 VERIFIED로 확정한다 — 인가 플래그 전이 + provenance 기록을
     * 함께 수행하는 {@link Company#markBusinessVerifiedByNts()}를 쓴다(#1324 P1).
     *
     * <p>⚠️ {@code markBusinessVerified()}(provenance 미기록)로 되돌리면 <b>이 배치가 무한 루프</b>가 된다 —
     * 대상 조회({@code CompanyRepository#findNtsReverifyTargets})가 provenance 로 판정하므로, 확인에
     * 성공하고도 {@code ntsOutcome} 이 SKIPPED 로 남아 매 회차 같은 회사를 다시 조회한다.
     */
    @Transactional
    public void markVerified(Long companyId) {
        companyRepository.findById(companyId).ifPresentOrElse(
                Company::markBusinessVerifiedByNts,
                () -> log.warn("사업자 재검증 VERIFIED 반영 대상 회사 소멸 — companyId={}", companyId));
    }

    /**
     * 국세청이 확정 불량(미등록·불일치·휴업·폐업)을 응답한 회사를 FAILED로 강등하고,
     * <b>같은 트랜잭션에서 오너의 유효 멤버십을 회수</b>한다(#1324 P1).
     *
     * <p><b>왜 멤버십까지 회수하나</b>: FAILED 만으로도 스코프 판정
     * ({@code CompanyMembershipRepository.existsEffectiveApprovedMembership} 의 VERIFIED 조건)이 닫히지만,
     * {@code company_memberships} 행이 APPROVED 로 남아 있으면 나중에 누군가 verification 을 손대는 순간
     * <b>즉시 스코프가 다시 열리는 지뢰</b>가 된다. V38 이 FAILED 회사에 오너 멤버십을 아예 만들지 않는
     * 것과 같은 판단이며, 방어 심층화(두 겹) 목적이다.
     *
     * <p>회수 범위는 <b>오너 멤버십</b>이다 — 오너 외 구성원의 멤버십은 같은 FAILED 게이트로 스코프가
     * 닫히며, 그들의 소속 정리는 정식 멤버십 관리 경로의 몫이라 이 배치에서 건드리지 않는다.
     *
     * <p>{@code APPROVED} 가 아닌 멤버십(이미 REVOKED/REJECTED/EXPIRED/PENDING)은 그대로 둔다 —
     * {@link CompanyMembership#revoke()} 는 APPROVED 에서만 허용되는 상태 전이라 가드 없이 호출하면
     * {@code DomainStateTransitionException} 으로 배치 1건이 실패한다(재실행 안전성 확보).
     */
    @Transactional
    public void markFailed(Long companyId) {
        companyRepository.findById(companyId).ifPresentOrElse(
                company -> failAndRevokeOwnerMembership(companyId, company),
                () -> log.warn("사업자 재검증 FAILED 반영 대상 회사 소멸 — companyId={}", companyId));
    }

    private void failAndRevokeOwnerMembership(Long companyId, Company company) {
        company.markBusinessVerificationFailed();
        companyMembershipRepository
                .findByCompanyIdAndUserId(companyId, company.getOwnerUserId())
                .filter(membership -> membership.getStatus() == CompanyMembershipStatus.APPROVED)
                .ifPresent(membership -> {
                    membership.revoke();
                    // 개인정보(사업자번호·대표자명·이메일)는 남기지 않는다 — 식별자만 기록.
                    log.info("사업자 확정 불량으로 오너 멤버십 회수 — companyId={}, membershipId={}",
                            companyId, membership.getId());
                });
    }
}
