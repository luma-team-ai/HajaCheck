package com.hajacheck.platformadmin.service;

import com.hajacheck.auth.entity.BusinessVerificationStatus;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyStatus;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.platformadmin.dto.CompanyOptionResponse;
import com.hajacheck.platformadmin.dto.CompanyVerificationResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플랫폼 관리자 콘솔 — 기업 조회(#576) + <b>회사 검증 무효화 킬스위치·복구</b>(#1367).
 *
 * <p><b>#1367 배경</b>: #1324 자동승인은 가입 즉시 회사 스코프를 연다. 사칭·오등록이 발견돼도 그것을
 * 되돌리는 앱 경로가 재검증 배치({@code PendingBusinessReverifyWriter#markFailed})뿐이었고, 그 배치가
 * 만든 {@code FAILED} 를 되돌리는 경로는 <b>아예 없었다</b> — 실제로 회사 1건이 6일간 전 API 차단된 뒤
 * 수동 SQL 로 복구됐다. 이 서비스가 그 왕복(무효화 ↔ 복구)을 앱 경로로 제공한다.
 *
 * <p><b>복구는 {@code PENDING} 까지만</b> 올린다 — 관리자가 국세청 판정을 무시하고 스코프를 직접 여는
 * 경로를 만들지 않는다. {@code PENDING} 은 재검증 대상 조회의 첫 갈래라 다음 배치가 국세청에 다시 물어
 * 재판정한다({@link Company#restoreBusinessVerificationByAdmin} javadoc).
 *
 * <p>인가는 SecurityConfig 의 {@code "/api/platform-admin/**" → hasRole(PLATFORM_ADMIN)} 매처가 필터
 * 단계에서 강제한다(이 서비스는 인가를 다시 판단하지 않는다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlatformAdminCompanyService {

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;

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
     */
    @Transactional
    public CompanyVerificationResponse revokeVerification(Long companyId, Long actorUserId, String reason) {
        Company company = lockCompany(companyId);
        if (company.getVerificationStatus() == BusinessVerificationStatus.FAILED) {
            throw new BusinessException(ErrorCode.COMPANY_VERIFICATION_ALREADY_REVOKED);
        }
        String previousOutcome = company.ntsOutcome().orElse(null);

        company.revokeBusinessVerificationByAdmin(reason);

        // 개인정보(사업자등록번호·대표자명·이메일) 금지 — 식별자와 라벨만 남긴다.
        log.warn("회사 검증 무효화(플랫폼 관리자) — companyId={}, actorUserId={}, 직전 ntsOutcome={}, reason={}",
                companyId, actorUserId, previousOutcome, reason);
        return toResponse(company);
    }

    /**
     * 검증 복구(#1367) — {@code FAILED} 회사를 {@code PENDING} 으로 되돌려 재검증 대상에 복귀시킨다.
     *
     * <p>⚠️ <b>개업일자 안전장치</b>: {@code businessStartDate} 가 없으면 거부한다. 재검증 대상 조회
     * ({@code CompanyRepository#findNtsReverifyTargets})가 {@code business_start_date IS NOT NULL} 을
     * 요구하므로, 개업일자 없는 회사를 PENDING 으로 되돌리면 배치가 영원히 잡지 못해 스코프가 <b>영구
     * 폐쇄</b>된다 — "복구했다"고 착각한 채 방치하게 되므로 FAILED 로 두는 것보다 나쁘다(#1329 실측 2건).
     */
    @Transactional
    public CompanyVerificationResponse restoreVerification(Long companyId, Long actorUserId, String reason) {
        Company company = lockCompany(companyId);
        if (company.getVerificationStatus() != BusinessVerificationStatus.FAILED) {
            throw new BusinessException(ErrorCode.COMPANY_VERIFICATION_NOT_REVOKED);
        }
        if (company.getBusinessStartDate() == null) {
            throw new BusinessException(ErrorCode.COMPANY_RESTORE_REQUIRES_BUSINESS_START_DATE);
        }

        company.restoreBusinessVerificationByAdmin(reason);

        log.warn("회사 검증 복구(플랫폼 관리자) — companyId={}, actorUserId={}, "
                        + "PENDING 복귀(다음 재검증 배치가 재판정), reason={}",
                companyId, actorUserId, reason);
        return toResponse(company);
    }

    private Company lockCompany(Long companyId) {
        return companyRepository.findByIdForUpdate(companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMPANY_NOT_FOUND));
    }

    private CompanyVerificationResponse toResponse(Company company) {
        long activeMemberCount =
                userRepository.countByCompanyIdAndStatus(company.getId(), UserStatus.ACTIVE);
        return CompanyVerificationResponse.from(company, activeMemberCount);
    }
}
