package com.hajacheck.platformadmin.service;

import com.hajacheck.auth.entity.AdminRestoreMode;
import com.hajacheck.auth.entity.BusinessVerificationStatus;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.demo.support.DemoCompanyProvenance;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.global.util.LogSanitizer;
import com.hajacheck.platformadmin.dto.CompanyOptionResponse;
import com.hajacheck.platformadmin.dto.CompanyVerificationResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플랫폼 관리자 콘솔 — 기업 조회(#576) + <b>회사 검증 무효화 킬스위치·복구·강제개방</b>(#1367).
 *
 * <p><b>#1367 배경</b>: #1324 자동승인은 가입 즉시 회사 스코프를 연다. 사칭·오등록이 발견돼도 그것을
 * 되돌리는 앱 경로가 재검증 배치({@code PendingBusinessReverifyWriter#markFailed})뿐이었고, 그 배치가
 * 만든 {@code FAILED} 를 되돌리는 경로는 <b>아예 없었다</b> — 실제로 회사 1건이 6일간 전 API 차단된 뒤
 * 수동 SQL 로 복구됐다. 이 서비스가 그 왕복을 앱 경로로 제공한다.
 *
 * <p><b>조치 3종의 역할 분담</b>:
 * <ul>
 *   <li>{@code revoke} — 사람 판단으로 회사 스코프를 <b>닫는다</b>(킬스위치).</li>
 *   <li>{@code restore} — 무효화를 <b>되무른다</b>. 관리자 자기 조치의 취소면 직전 상태로 즉시 복원,
 *       그 밖(배치 강등 등)은 {@code PENDING} 으로 되돌려 다음 배치가 국세청에 재판정을 맡긴다
 *       ({@link AdminRestoreMode}).</li>
 *   <li>{@code override} — 국세청이 계속 부정 판정(MISMATCH 등)을 주지만 사람이 실물을 확인한 회사의
 *       스코프를 <b>연다</b>. restore 로는 열 수 없는 유일한 사각지대를 메운다.</li>
 * </ul>
 *
 * <p>인가는 SecurityConfig 의 {@code "/api/platform-admin/**" → hasRole(PLATFORM_ADMIN)} 매처가 필터
 * 단계에서 강제한다(이 서비스는 인가를 다시 판단하지 않는다).
 *
 * <p><b>감사 로그 규약</b>: 개인정보(사업자등록번호·대표자명·이메일) 금지 — 식별자·라벨·사유만 남긴다.
 * 관리자 자유 입력인 {@code reason} 은 {@link LogSanitizer} 로 살균해서 찍는다(CWE-117 — 살균하지 않으면
 * 사유에 개행을 실어 <b>존재하지 않는 조치를 감사 로그에 위조</b>할 수 있다). actor 는 로그뿐 아니라
 * provenance 에도 남긴다 — 로그 보존 한계로 사후 규명이 불가능했던 전례가 이 기능의 출발점이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlatformAdminCompanyService {

    private final CompanyRepository companyRepository;
    private final CompanyMembershipRepository companyMembershipRepository;

    /** 심사 승인(APPROVED)된 기업만 사용자 등록 모달의 배정 후보로 노출한다(#576). */
    public List<CompanyOptionResponse> listAssignableCompanies() {
        return companyRepository.findByStatusOrderByNameAsc(CompanyStatus.APPROVED).stream()
                .map(CompanyOptionResponse::from)
                .toList();
    }

    /** 차단 판단 근거 조회(#1367) — 읽기 전용이라 행 잠금 없이 {@code findById} 로 읽는다. */
    public CompanyVerificationResponse getVerification(Long companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMPANY_NOT_FOUND));
        return toResponse(company);
    }

    /**
     * 검증 무효화 킬스위치(#1367) — 회사 스코프를 즉시 닫는다.
     *
     * <p>동시성: 상태 검사 후 전이하므로 {@code findByIdForUpdate} 로 회사 행을 먼저 잠근다
     * ({@code PlatformAdminUserService} 의 마지막 ADMIN 보호와 동일 패턴). 잠그지 않으면 같은 회사를
     * 대상으로 한 revoke/restore 두 요청이 서로의 커밋 전 스냅샷을 보고 둘 다 가드를 통과해, 나중에
     * 커밋된 쪽이 앞선 조치의 사유·{@code ntsOutcomeBeforeRevoke} 를 덮을 수 있다.
     *
     * <p>⚠️ 배치와의 경합은 이 잠금만으로 끝나지 않는다 — Postgres 쓰기 잠금은 평문 SELECT 를 막지 않아
     * 배치가 이미 읽어 둔 목록으로 이 무효화를 덮을 수 있었다. 그쪽 방어는
     * {@code PendingBusinessReverifyWriter#markVerified} 의 관리자 무효화 우선 가드가 담당한다(P1-B).
     */
    @Transactional
    public CompanyVerificationResponse revokeVerification(Long companyId, Long actorUserId, String reason) {
        Company company = lockCompany(companyId);
        if (company.getVerificationStatus() == BusinessVerificationStatus.FAILED) {
            throw new BusinessException(ErrorCode.COMPANY_VERIFICATION_ALREADY_REVOKED);
        }
        String previousOutcome = company.ntsOutcome().orElse(null);

        company.revokeBusinessVerificationByAdmin(reason, actorUserId);

        log.warn("회사 검증 무효화(플랫폼 관리자) — companyId={}, actorUserId={}, 직전 ntsOutcome={}, reason={}",
                companyId, actorUserId, previousOutcome, LogSanitizer.sanitize(reason));
        return toResponse(company);
    }

    /**
     * 검증 복구(#1367) — 무효화(FAILED)를 되무른다. 두 경로로 갈린다({@link AdminRestoreMode}).
     *
     * <p><b>관리자 자기 조치의 취소</b>({@code RESTORED_TO_VERIFIED})는 직전 상태로 즉시 복원하므로
     * 배치에 의존하지 않는다 → 아래 가드를 적용하지 않는다. 오조작 revoke 한 건 때문에 정상 회사가 다음
     * 배치 회차(하루 1회)까지 멈춰 있는 것을 막는 것이 이 분기의 목적이기 때문이다.
     *
     * <p><b>PENDING 복귀 경로</b>({@code RESTORED_TO_PENDING})는 재검증 배치가 다시 집어 줘야 스코프가
     * 열린다. 그래서 <b>"배치가 실제로 집을 수 있는 회사인가"를 대상 쿼리 조건과 1:1로</b> 먼저 검사한다.
     * 하나라도 어긋난 채 PENDING 으로 되돌리면 <b>영구 고착</b>돼 "복구했다"고 착각한 채 스코프가 영영
     * 닫혀 있게 된다 — FAILED 로 두는 것보다 나쁘다.
     * <ul>
     *   <li>{@code businessStartDate == null} → 쿼리의 {@code business_start_date is not null} 위반
     *       (#1329 에서 prod 2건 실측)</li>
     *   <li>{@code status == REJECTED} → 쿼리의 {@code status <> 'REJECTED'} 위반</li>
     *   <li>데모 시드 회사 → 쿼리에는 걸리지만 스케줄러가 국세청 호출 <b>전에</b> 스킵하고
     *       ({@code DemoCompanyProvenance#isDemoSeeded}), 데모 자가복구
     *       ({@code DemoSeedService#healFailedVerificationIfNeeded})는 <b>FAILED 만</b> 처리하므로
     *       PENDING 은 아무도 건드리지 않는다 → 영구 고착. 이 경우는 override 로 안내한다.</li>
     * </ul>
     */
    @Transactional
    public CompanyVerificationResponse restoreVerification(Long companyId, Long actorUserId, String reason) {
        Company company = lockCompany(companyId);
        if (company.getVerificationStatus() != BusinessVerificationStatus.FAILED) {
            throw new BusinessException(ErrorCode.COMPANY_VERIFICATION_NOT_REVOKED);
        }
        if (!company.isAdminRevokeUndoable()) {
            requireReverifiable(company);
        }

        AdminRestoreMode mode = company.restoreBusinessVerificationByAdmin(reason, actorUserId);

        if (mode == AdminRestoreMode.RESTORED_TO_VERIFIED) {
            log.warn("회사 검증 복구(플랫폼 관리자) — companyId={}, actorUserId={}, "
                            + "관리자 무효화 취소로 직전 검증 상태(VERIFIED) 즉시 복원, reason={}",
                    companyId, actorUserId, LogSanitizer.sanitize(reason));
        } else {
            log.warn("회사 검증 복구(플랫폼 관리자) — companyId={}, actorUserId={}, "
                            + "PENDING 복귀(다음 재검증 배치가 재판정), reason={}",
                    companyId, actorUserId, LogSanitizer.sanitize(reason));
        }
        return toResponse(company);
    }

    /**
     * 검증 강제개방(#1367 P1-A) — 국세청이 계속 부정 판정을 주지만 사람이 실물을 확인한 회사의 스코프를 연다.
     *
     * <p><b>왜 restore 로는 안 되는가</b>: 대표자 변경으로 {@code MISMATCH} 가 나는 회사는 restore 후에도
     * 매 회차 MISMATCH 를 받고(엔티티에 대표자명 수정 경로가 없다), 새 정책상 MISMATCH 는 자동 강등도
     * 자동 승격도 하지 않아 <b>PENDING 에 영구 고착</b>된다. 이 PR 이 없애려던 수동 SQL 로 회귀하는 지점이라
     * 사람이 여는 경로가 반드시 필요하다.
     *
     * <p><b>안전장치 = 자동 재차단</b>: {@code ntsOutcome = ADMIN_OVERRIDE_VERIFIED} 는 인정 화이트리스트
     * 밖이라 인증 배지는 꺼진 채 유지되고 <b>재검증 대상에 계속 남는다</b> → 국세청이 나중에 미등록·폐업을
     * 확정하면 배치가 자동으로 다시 차단한다({@code PendingBusinessReverifyWriter#markFailed} 가 override
     * 회사에는 상태 가드를 두지 않는 이유).
     *
     * <p>⚠️ <b>경고만 하고 막지 않는 두 경우</b>(관리자 조치의 자유도를 유지하되, 조치 결과가 기대와
     * 다르다는 사실은 반드시 남긴다):
     * <ul>
     *   <li><b>자동 재차단 없음</b> — 개업일자가 없거나 반려·데모 시드 회사면 배치가 대상으로 조회하지
     *       못하거나 국세청 호출 전에 스킵하므로 위 안전장치가 <b>동작하지 않는다</b>. 진단 응답의
     *       {@code reverifiableByBatch} 로도 사후 확인할 수 있다.</li>
     *   <li><b>스코프가 실제로는 열리지 않음</b> — 스코프 판정은 {@code companies.status = APPROVED} 도
     *       요구하므로({@code CompanyMembershipRepository#existsEffectiveApprovedMembership}),
     *       {@code PENDING_REVIEW}·{@code REJECTED} 회사에 override 하면 {@code verificationStatus} 만
     *       VERIFIED 가 되고 <b>회사 스코프는 닫힌 채</b>다. "열었다"는 응답을 받고 방치하는 것이 위험해
     *       별도 경고로 구분해 남긴다.</li>
     * </ul>
     */
    @Transactional
    public CompanyVerificationResponse overrideVerification(Long companyId, Long actorUserId, String reason) {
        Company company = lockCompany(companyId);
        if (company.getVerificationStatus() == BusinessVerificationStatus.VERIFIED) {
            throw new BusinessException(ErrorCode.COMPANY_VERIFICATION_ALREADY_VERIFIED);
        }
        String previousOutcome = company.ntsOutcome().orElse(null);

        company.overrideBusinessVerificationByAdmin(reason, actorUserId);

        log.warn("회사 검증 강제개방(플랫폼 관리자) — companyId={}, actorUserId={}, 직전 ntsOutcome={}, "
                        + "인증 배지는 꺼진 채 유지되고 재검증 대상에 남아 확정 불량 시 자동 재차단된다, reason={}",
                companyId, actorUserId, previousOutcome, LogSanitizer.sanitize(reason));
        findReverifyBlocker(company).ifPresent(blocker ->
                log.warn("회사 검증 강제개방 — 자동 재차단 안전장치 없음(재검증 배치가 이 회사를 조회하지"
                                + " 못한다). 국세청이 나중에 확정 불량을 주더라도 자동으로 다시 막히지"
                                + " 않으므로 운영이 직접 추적해야 한다. companyId={}, 사유={}",
                        companyId, blocker));
        // 스코프 판정은 companies.status = APPROVED 도 요구한다 — 검증 플래그만 열어서는 실제로 아무것도
        // 열리지 않는다("열었다"는 응답을 받고 방치하게 되는 것이 위험하므로 반드시 경고로 남긴다).
        if (company.getStatus() != CompanyStatus.APPROVED) {
            log.warn("회사 검증 강제개방 — 회사 status 가 APPROVED 가 아니라 override 만으로는 회사 스코프가"
                            + " 열리지 않는다(스코프 판정은 status=APPROVED 도 요구한다)."
                            + " companyId={}, status={}",
                    companyId, company.getStatus());
        }
        return toResponse(company);
    }

    /**
     * <b>재검증 배치가 이 회사를 집을 수 없게 만드는 사유</b> — 없으면 empty(#1367).
     *
     * <p><b>이 메서드가 유일한 판정 지점이다.</b> 같은 조건을 세 곳(복구 가드 · override 경고 · 진단 응답의
     * {@code reverifiableByBatch})이 각자 구현하면 그 순간부터 드리프트가 시작돼, "가드는 막는데 응답은
     * 가능하다고 표시"하는 모순이 조용히 생긴다. 조건은 {@code CompanyRepository#findNtsReverifyTargets}
     * 의 where 절 + 스케줄러의 데모 스킵과 1:1로 대응한다.
     */
    private Optional<ReverifyBlocker> findReverifyBlocker(Company company) {
        if (company.getBusinessStartDate() == null) {
            return Optional.of(ReverifyBlocker.NO_BUSINESS_START_DATE);
        }
        if (company.getStatus() == CompanyStatus.REJECTED) {
            return Optional.of(ReverifyBlocker.REJECTED);
        }
        if (DemoCompanyProvenance.isDemoSeeded(company)) {
            return Optional.of(ReverifyBlocker.DEMO_SEEDED);
        }
        return Optional.empty();
    }

    /** PENDING 복귀 경로 전용 가드 — 배치가 집을 수 없는 회사를 PENDING 으로 되돌리면 영구 고착된다. */
    private void requireReverifiable(Company company) {
        findReverifyBlocker(company).ifPresent(blocker -> {
            throw switch (blocker) {
                // 개업일자는 보정하면 복구할 수 있어 별도 코드로 남긴다(관리자 행동이 다르다).
                case NO_BUSINESS_START_DATE ->
                        new BusinessException(ErrorCode.COMPANY_RESTORE_REQUIRES_BUSINESS_START_DATE);
                case REJECTED -> new BusinessException(ErrorCode.COMPANY_RESTORE_NOT_REVERIFIABLE,
                        "반려된 기업은 재검증 대상이 아니라 복구해도 검증이 재개되지 않습니다.");
                case DEMO_SEEDED -> new BusinessException(ErrorCode.COMPANY_RESTORE_NOT_REVERIFIABLE,
                        "데모 기업은 재검증 배치가 건너뛰므로 복구해도 검증이 재개되지 않습니다. "
                                + "강제개방(override)을 사용해 주세요.");
            };
        });
    }

    private Company lockCompany(Long companyId) {
        return companyRepository.findByIdForUpdate(companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMPANY_NOT_FOUND));
    }

    private CompanyVerificationResponse toResponse(Company company) {
        long effectiveMemberCount = companyMembershipRepository
                .countEffectiveApprovedMembers(company.getId(), Instant.now());
        return CompanyVerificationResponse.from(
                company, effectiveMemberCount, findReverifyBlocker(company).isEmpty());
    }

    /** 재검증 배치가 회사를 집지 못하게 만드는 사유 — 가드 예외 선택과 진단 응답이 공유한다. */
    private enum ReverifyBlocker {
        /** 대상 쿼리의 {@code business_start_date is not null} 위반. */
        NO_BUSINESS_START_DATE,
        /** 대상 쿼리의 {@code status <> 'REJECTED'} 위반. */
        REJECTED,
        /** 쿼리에는 걸리지만 스케줄러가 국세청 호출 전에 스킵한다({@code DemoCompanyProvenance}). */
        DEMO_SEEDED
    }
}
