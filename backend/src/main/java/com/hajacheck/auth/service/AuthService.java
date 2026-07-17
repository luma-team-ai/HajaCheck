package com.hajacheck.auth.service;

import com.hajacheck.auth.dto.UserResponse;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.Instant;
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
     * (users.company_id는 요금제 판단의 단독 근거는 아니지만(table_design.md §2.6),
     * 배정 범위를 같은 회사로 제한하는 교차 확인 용도로는 충분하다.)
     * 미존재/정지/역할·소속 불충족 모두 이 코드로 통일 응답(리소스 존재 여부 열거 방지 — FacilityService 패턴과 동일).
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
        if (assignee.getStatus() != UserStatus.ACTIVE || !assignableRole || !sameCompany) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_INSPECTOR);
        }
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
    }
}
