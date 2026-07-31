package com.hajacheck.bizverify.scheduler;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.bizverify.service.NtsVerificationOutcome;
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
     * 국세청이 확정 불량(미등록·불일치·휴업·폐업)을 응답한 회사를 FAILED로 강등한다.
     *
     * <p><b>⚠️ 멤버십은 회수하지 않는다 — 의도적 선택이다(#1324 리뷰 결정). 다시 넣지 말 것.</b>
     * "APPROVED 로 남은 멤버십 행이 지뢰"라는 직관 때문에 회수를 넣기 쉬운데, 아래 세 근거로 되돌렸다:
     * <ul>
     *   <li><b>즉시적 보안 이득이 0</b> — 스코프 판정
     *       ({@code CompanyMembershipRepository.existsEffectiveApprovedMembership})과 동일 불변식의 DB
     *       트리거({@code check_inspection_assigned_inspector_company})가 <b>둘 다</b>
     *       {@code companies.verification_status = 'VERIFIED'} 를 요구한다. 즉 이 메서드의 FAILED 전이
     *       한 줄로 오너뿐 아니라 <b>전 구성원의 회사 스코프가 이미 닫힌다</b>. 회수는 차단에 아무것도
     *       더하지 않는다.</li>
     *   <li><b>비가역</b> — {@link com.hajacheck.auth.entity.CompanyMembership#revoke()} 로 REVOKED 가 된
     *       행을 런타임에 되돌릴 코드가 <b>없다</b>({@code reinvite()} 프로덕션 호출부 0건 ·
     *       {@code approvedOwner()} 는 가입 경로 1곳뿐 · {@code Company.approve()} 미배선). 배치가
     *       자동으로 만드는 상태가 수동 DB 조작 없이는 복구 불가여선 안 된다.</li>
     *   <li><b>오판이 자가치유되지 않는다</b> — FAILED 는 재검증 대상 조회
     *       ({@code CompanyRepository#findNtsReverifyTargets})의 두 갈래(PENDING · 증명 불가 VERIFIED)
     *       어디에도 걸리지 않아 <b>영구 제외</b>된다. 국세청 오판(대표자 변경·레거시 오타 → MISMATCH,
     *       계절 휴업 → SUSPENDED)이 섞이면 회사가 스스로 회복할 경로가 없는데, 거기에 비가역 회수까지
     *       얹으면 피해가 배가된다.</li>
     * </ul>
     *
     * <p>또한 <b>오너만</b> 회수하면 상태가 뒤집힌다 — 나중에 verification 이 복구될 때 정당한 오너만
     * REVOKED 로 잠기고 나머지 구성원은 전원 즉시 복권된다. 그렇다고 "전원 회수"로 가면 비가역성만
     * 커진다. 그래서 회수를 하지 않는 쪽으로 통일했다.
     *
     * <p>FAILED 전이 → (필요 시) 멤버십 정리 → 소명·복구까지 <b>왕복을 완성하는 정식 설계는 후속 이슈
     * #1367</b>로 분리했다. 그 전까지는 아래 경고 로그로 운영이 사후 판단한다.
     */
    @Transactional
    public void markFailed(Long companyId, NtsVerificationOutcome outcome) {
        companyRepository.findById(companyId).ifPresentOrElse(
                company -> {
                    company.markBusinessVerificationFailed();
                    // 회수를 하지 않는 것이지 무시하는 게 아니다 — 운영이 사후 판단할 수 있게 남긴다.
                    // 개인정보(사업자번호·대표자명·이메일) 금지: 식별자와 결과 라벨만 기록한다.
                    log.warn("사업자 진위확인 확정 불량 — 회사 스코프 차단(FAILED). 멤버십은 유지한다"
                                    + "(복구 경로 부재로 비가역 회수를 하지 않음, 후속 #1367)."
                                    + " companyId={}, outcome={}",
                            companyId, outcome);
                },
                () -> log.warn("사업자 재검증 FAILED 반영 대상 회사 소멸 — companyId={}", companyId));
    }
}
