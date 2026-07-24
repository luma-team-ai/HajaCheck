package com.hajacheck.auth.service;

import com.hajacheck.auth.dto.AssignableUserResponse;
import com.hajacheck.auth.dto.UserResponse;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증 부가 로직 — 로그인 시각 기록·내 정보 조회.
 * (인증 자체는 AuthenticationManager/OAuth2 필터가 수행, 세션 저장은 컨트롤러.)
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final CompanyMembershipRepository companyMembershipRepository;

    /**
     * 로그인 성공 후 lastLoginAt 갱신(dirty checking).
     * 응답 생성과 분리해, 이 write 가 실패해도 이미 발급된 세션·응답과 불일치가 나지 않도록
     * 컨트롤러에서 best-effort 로 호출한다(실패 시 로깅만).
     */
    @Transactional
    public void updateLastLogin(Long userId) {
        findUser(userId).updateLastLogin(Instant.now());
    }

    public UserResponse getMe(Long userId) {
        return UserResponse.from(findUser(userId));
    }

    /**
     * 점검 담당자 배정 가능 여부 검증(dev-05-02, 점검 회차 생성) — docs/design/db/table_design.md
     * §inspections: "assigned_inspector_id가 가리키는 사용자는 애플리케이션에서
     * users.status=ACTIVE AND role IN (INSPECTOR, ADMIN)인지 검증한다."
     * 역할/상태 조건에 더해, 요청자와 같은 회사(companyId) 소속인지도 확인한다 — 이 검증이 없으면
     * 임의의 활성 점검자·관리자를 아무 시설물에나 배정할 수 있는 권한 범위 문제가 생긴다.
     * users.company_id는 조회 편의 포인터일 뿐 권한의 단독 근거가 아니므로, 요청자와 배정자 모두
     * company_memberships의 유효한 APPROVED 멤버십을 가져야 한다.
     * 요청자 또는 배정자의 미존재/정지/역할·소속 불충족 모두 이 코드로 통일 응답
     * (리소스 존재 여부 열거 방지 — FacilityService 패턴과 동일).
     */
    public void validateAssignableInspector(Long requesterUserId, Long assignedInspectorId) {
        User requester = findUser(requesterUserId);
        User assignee = userRepository.findById(assignedInspectorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_INSPECTOR));
        boolean assignableRole = assignee.getRole() == Role.INSPECTOR || assignee.getRole() == Role.ADMIN;
        boolean sameCompany = requester.getCompanyId() != null
                && requester.getCompanyId().equals(assignee.getCompanyId());
        // 문서 요구사항 문구("status=ACTIVE")와 코드가 정확히 대응하도록 명시 비교로 작성
        // (isSuspended() 부정과 현재는 결과가 같지만, status 값이 늘어나도 의도가 코드에서 바로 드러난다).
        if (requester.getStatus() != UserStatus.ACTIVE
                || assignee.getStatus() != UserStatus.ACTIVE
                || !assignableRole
                || !sameCompany) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_INSPECTOR);
        }

        Instant now = Instant.now();
        Long companyId = requester.getCompanyId();
        boolean requesterMembershipEffective = companyMembershipRepository.existsEffectiveApprovedMembership(
                companyId, requesterUserId, now);
        boolean assigneeMembershipEffective = companyMembershipRepository.existsEffectiveApprovedMembership(
                companyId, assignedInspectorId, now);
        if (!requesterMembershipEffective || !assigneeMembershipEffective) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_INSPECTOR);
        }
    }

    /**
     * 배정 가능한 회사 소속 사용자 목록(#690) — validateAssignableInspector 와 동일 자격 조건
     * (활성·INSPECTOR/ADMIN 역할·유효 APPROVED 멤버십)을 목록으로 반환한다.
     * companyId 는 LoginUser(세션 인증 결과)에서만 취득 — 요청 파라미터로 받지 않아 cross-company
     * 열람을 원천 차단한다(FacilityController 의 companyId 스코프 패턴과 동일).
     *
     * PR머신 P2 픽스: users.company_id 는 조회 편의 포인터일 뿐 권한의 단독 근거가 아니다
     * (validateAssignableInspector 의 동일 원칙 — 클래스 상단 주석 참고). 멤버십이 revoke/만료된
     * 뒤에도 세션(LoginUser)이 아직 유효하면 companyId 포인터만으로 회사 전체 명부를 열람할 수
     * 있는 인가 갭이 있었다 — 요청자 본인의 유효 APPROVED 멤버십을 먼저 재확인한다.
     */
    public List<AssignableUserResponse> listAssignableUsers(Long companyId, Long requesterUserId) {
        Instant now = Instant.now();
        boolean requesterMembershipEffective = companyId != null
                && companyMembershipRepository.existsEffectiveApprovedMembership(companyId, requesterUserId, now);
        if (!requesterMembershipEffective) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_INSPECTOR);
        }
        return companyMembershipRepository
                .findAssignableUsersInCompany(companyId, now)
                .stream()
                .map(AssignableUserResponse::from)
                .toList();
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
    }
}
