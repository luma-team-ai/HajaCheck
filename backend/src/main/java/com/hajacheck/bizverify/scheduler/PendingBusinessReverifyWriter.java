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
     *
     * <p><b>⚠️ 관리자 무효화가 배치보다 우선한다(#1367 P1-B)</b> — 이 갱신은 관리자 킬스위치를
     * <b>조용히 덮어쓸 수 있었다</b>: 배치는 회차 시작(T0)에 대상 목록을 한 번에 읽고 건별로 처리하므로,
     * 그 사이(T1)에 관리자가 revoke 한 회사를 T2 에 VERIFIED 로 되돌린다. revoke 가
     * {@code findByIdForUpdate}(PESSIMISTIC_WRITE)로 행을 잠가도 <b>Postgres 의 쓰기 잠금은 평문 SELECT 를
     * 막지 않고</b>, {@code @Version} 도 무력하다(revoke 가 먼저 커밋되면 이 트랜잭션은 최신 버전을 읽어
     * 충돌 없이 덮는다). 게다가 결과가 <b>무증상</b>이다 — 덮인 회사는 {@code ntsOutcome=VERIFIED} 가 되어
     * 재검증 대상에서 영구 이탈하고 인증 배지까지 켜지며 다음 회차 경보도 없다.
     * <p>그래서 ①{@code findByIdForUpdate} 로 <b>잠근 뒤 최신 상태를 읽고</b> ②관리자 무효화 상태면
     * 덮지 않고 건너뛴다. <b>사람 판단이 배치 판단보다 우선한다</b>는 규칙을 코드로 고정한 것이다.
     */
    @Transactional
    public void markVerified(Long companyId) {
        companyRepository.findByIdForUpdate(companyId).ifPresentOrElse(
                company -> {
                    if (company.isAdminRevoked()) {
                        // 스탬프는 남긴다 — 건너뛴 회사가 대기열 앞자리를 고정 점유하지 않게 한다(#1367 P1-C).
                        company.stampNtsReverifyAttempt();
                        log.warn("사업자 재검증 VERIFIED 반영 건너뜀 — 관리자 무효화가 우선한다"
                                        + "(배치 판단으로 사람 조치를 덮지 않는다). companyId={}", companyId);
                        return;
                    }
                    company.markBusinessVerifiedByNts();
                    company.stampNtsReverifyAttempt();
                },
                () -> log.warn("사업자 재검증 VERIFIED 반영 대상 회사 소멸 — companyId={}", companyId));
    }

    /**
     * 재검증 배치가 이 회사를 처리 시도했음을 스탬프한다(#1367 P1-C) — 상태를 바꾸지 않는 경로
     * (데모 스킵 · SKIPPED · 처리 예외)에서 호출한다.
     *
     * <p>스탬프가 없으면 그 회사는 <b>영원히 대기열 앞자리를 점유</b>해 뒤쪽(신규 가입) 회사가 재검증되지
     * 않는다({@code CompanyRepository#findNtsReverifyTargets} 정렬 javadoc). 행을 잠그고 읽는 이유는
     * markVerified 와 같다 — 스탬프도 {@code ocr_raw} 컬럼 전체를 다시 쓰므로, 잠그지 않으면 그 사이
     * 커밋된 관리자 조치를 통째로 잃는다(lost update).
     */
    @Transactional
    public void stampAttempt(Long companyId) {
        companyRepository.findByIdForUpdate(companyId)
                .ifPresent(Company::stampNtsReverifyAttempt);
    }

    /**
     * 자동 강등하지 않는 경보 판정(MISMATCH/SUSPENDED)을 회사에 기록한다(#1367 P2-3) — 시도 스탬프 포함.
     *
     * <p>"경보만 남긴다"는 정책은 자동 통제를 사람 판단으로 옮긴 것인데, 경보가 DB 에 남지 않으면
     * <b>사람에게 도달하는 신호가 로그뿐</b>이라 진단 API 로도 확인할 수 없다. {@code ntsOutcome} 은
     * 건드리지 않는다 — 강등하지 않기로 한 판정을 인가 근거 키에 쓰면 대상 집합이 흔들린다.
     */
    @Transactional
    public void stampAlert(Long companyId, NtsVerificationOutcome outcome) {
        companyRepository.findByIdForUpdate(companyId)
                .ifPresent(company -> company.stampNtsReverifyAlert(outcome.name()));
    }

    /**
     * 국세청이 <b>확정 불량</b>(미등록·폐업)을 응답한 회사를 FAILED로 강등한다.
     *
     * <p>⚠️ <b>호출 범위가 좁혀졌다</b>(#1367): 불일치(MISMATCH)·휴업(SUSPENDED)은 대표자 변경·계절
     * 휴업·비영리 고유번호증 같은 <b>정상 사업 변동</b>일 수 있어 스케줄러가 더 이상 이 메서드를 호출하지
     * 않고 경보만 남긴다({@code PendingBusinessReverifyScheduler} javadoc). 이 메서드 자체는 outcome 을
     * 판단하지 않으므로 시그니처는 그대로다 — 어떤 outcome 을 확정 불량으로 볼지는 호출부의 정책이다.
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
     * <p><b>복구 왕복은 #1367 로 완성됐다</b> — 이 강등을 사람이 되돌리는 경로는
     * {@code POST /api/platform-admin/companies/{id}/verification/restore}
     * ({@link com.hajacheck.auth.entity.Company#restoreBusinessVerificationByAdmin})이며, FAILED 를
     * PENDING 으로 되돌려 <b>다음 회차 재검증 대상에 복귀</b>시킨다. 멤버십을 회수하지 않았기 때문에 그
     * 복구 한 번으로 전 구성원의 스코프가 그대로 다시 열린다(회수했다면 복권 코드가 없어 반쪽이 된다).
     */
    @Transactional
    public void markFailed(Long companyId, NtsVerificationOutcome outcome) {
        companyRepository.findByIdForUpdate(companyId).ifPresentOrElse(
                company -> {
                    // ⚠️ markVerified 와 달리 상태 가드를 두지 않는다 — 관리자 override 로 열린 회사를
                    // 국세청 확정 불량으로 다시 내리는 것은 막아야 할 덮어쓰기가 아니라 override 의
                    // 안전장치(자동 재차단) 그 자체다(#1367 P1-A). 다만 로그로 구분해 남긴다.
                    boolean wasAdminOverridden = company.isAdminOverridden();
                    company.markBusinessVerificationFailed();
                    company.stampNtsReverifyAttempt();
                    // 회수를 하지 않는 것이지 무시하는 게 아니다 — 운영이 사후 판단할 수 있게 남긴다.
                    // 개인정보(사업자번호·대표자명·이메일) 금지: 식별자와 결과 라벨만 기록한다.
                    if (wasAdminOverridden) {
                        log.warn("관리자 override 회사를 국세청 확정 불량으로 재차단 — override 의 자동"
                                        + " 안전장치가 동작했다. companyId={}, outcome={}",
                                companyId, outcome);
                    } else {
                        log.warn("사업자 진위확인 확정 불량 — 회사 스코프 차단(FAILED). 멤버십은 유지한다"
                                        + "(비가역 회수를 하지 않음 — 복구는 플랫폼 관리자 restore API)."
                                        + " companyId={}, outcome={}",
                                companyId, outcome);
                    }
                },
                () -> log.warn("사업자 재검증 FAILED 반영 대상 회사 소멸 — companyId={}", companyId));
    }
}
