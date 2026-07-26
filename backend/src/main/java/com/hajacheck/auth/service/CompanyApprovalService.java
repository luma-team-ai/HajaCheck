package com.hajacheck.auth.service;

import com.hajacheck.auth.dto.CompanyApprovalResponse;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기업 가입 승인/반려(#363, PR #264 후속) — {@link Company#approve}/{@link Company#reject}가
 * "현재 미배선"이라 남겨둔 상태 전이를 실제 서비스 계층에 연결한다.
 *
 * <p>⚠️ 계약(Company/CompanyMembership 엔티티 javadoc 경고 그대로): {@code Company.status}와
 * {@code CompanyMembership.status}는 독립된 두 상태 머신이라, 승인/반려 시 반드시 같은 트랜잭션에서
 * 오너 멤버십도 함께 전이시켜야 한다. 승인 시에는 추가로 {@code users.company_id}까지 배선해야
 * ({@link CompanyAccountWriter} 가 가입 시점엔 배선을 미뤄뒀으므로) 오너가 그제서야 회사 스코프
 * 관리자 권한(/api/admin/**)을 얻는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompanyApprovalService {

    private final CompanyRepository companyRepository;
    private final CompanyMembershipRepository companyMembershipRepository;
    private final UserRepository userRepository;

    @Transactional
    public CompanyApprovalResponse approve(Long reviewerUserId, Long companyId) {
        Company company = findCompany(companyId);
        // 사업자등록정보 검증(VERIFIED) + 심사대기(PENDING_REVIEW) 불변식은 Company.approve() 가 강제한다.
        company.approve(reviewerUserId);

        CompanyMembership ownerMembership = findOwnerMembership(company);
        ownerMembership.approve();

        User owner = userRepository.findById(company.getOwnerUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        // 승인 시점에야 회사 스코프 관리자 권한을 부여한다(#363) — 그 전까지 owner.companyId는 null이라
        // AdminUserService/AdminPlanService 등의 requireCompanyId 가드가 관리자 콘솔 접근을 막는다.
        owner.assignToCompany(company.getId());

        return CompanyApprovalResponse.from(company);
    }

    @Transactional
    public CompanyApprovalResponse reject(Long reviewerUserId, Long companyId, String reason) {
        Company company = findCompany(companyId);
        company.reject(reviewerUserId, reason);

        CompanyMembership ownerMembership = findOwnerMembership(company);
        ownerMembership.reject();

        return CompanyApprovalResponse.from(company);
    }

    private Company findCompany(Long companyId) {
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMPANY_NOT_FOUND));
    }

    // 가입 시점(CompanyAccountWriter)에 항상 오너의 PENDING 멤버십을 만들어두므로 정상 흐름에서는
    // 반드시 존재한다 — 없다면 가입 경로 자체가 깨진 데이터 정합성 문제라 INTERNAL_ERROR로 표면화한다.
    private CompanyMembership findOwnerMembership(Company company) {
        return companyMembershipRepository.findByCompanyIdAndUserId(company.getId(), company.getOwnerUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
    }
}
