package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * validateAssignableInspector 단위 테스트(dev-05-02) — 역할/상태/소속 회사 3가지 조건을 모두 검증.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AuthService authService;

    // 요청자는 companyId만 검증에 쓰인다(역할/정지 여부는 assignee 쪽만 확인).
    private static User requesterOf(Long companyId) {
        User user = mock(User.class);
        when(user.getCompanyId()).thenReturn(companyId);
        return user;
    }

    private static User assigneeOf(Long companyId, Role role, boolean suspended) {
        User user = mock(User.class);
        when(user.getCompanyId()).thenReturn(companyId);
        when(user.getRole()).thenReturn(role);
        when(user.getStatus()).thenReturn(suspended ? UserStatus.SUSPENDED : UserStatus.ACTIVE);
        return user;
    }

    @Test
    void validateAssignableInspector_같은회사_INSPECTOR_ACTIVE_통과() {
        User requester = requesterOf(1L);
        User assignee = assigneeOf(1L, Role.INSPECTOR, false);
        when(userRepository.findById(100L)).thenReturn(Optional.of(requester));
        when(userRepository.findById(200L)).thenReturn(Optional.of(assignee));

        authService.validateAssignableInspector(100L, 200L);
    }

    @Test
    void validateAssignableInspector_같은회사_ADMIN_ACTIVE_통과() {
        User requester = requesterOf(1L);
        User assignee = assigneeOf(1L, Role.ADMIN, false);
        when(userRepository.findById(100L)).thenReturn(Optional.of(requester));
        when(userRepository.findById(200L)).thenReturn(Optional.of(assignee));

        authService.validateAssignableInspector(100L, 200L);
    }

    @Test
    void validateAssignableInspector_대상사용자없음_AUTH_INVALID_INSPECTOR() {
        // assignee 조회가 먼저 비어있음으로 끝나 requester.getCompanyId()까지 도달하지 않는다 — 스텁 없이 mock만 사용.
        User requester = mock(User.class);
        when(userRepository.findById(100L)).thenReturn(Optional.of(requester));
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.validateAssignableInspector(100L, 999L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_INSPECTOR));
    }

    @Test
    void validateAssignableInspector_역할이USER_AUTH_INVALID_INSPECTOR() {
        User requester = requesterOf(1L);
        User assignee = assigneeOf(1L, Role.USER, false);
        when(userRepository.findById(100L)).thenReturn(Optional.of(requester));
        when(userRepository.findById(200L)).thenReturn(Optional.of(assignee));

        assertThatThrownBy(() -> authService.validateAssignableInspector(100L, 200L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_INSPECTOR));
    }

    @Test
    void validateAssignableInspector_정지된계정_AUTH_INVALID_INSPECTOR() {
        User requester = requesterOf(1L);
        User assignee = assigneeOf(1L, Role.INSPECTOR, true);
        when(userRepository.findById(100L)).thenReturn(Optional.of(requester));
        when(userRepository.findById(200L)).thenReturn(Optional.of(assignee));

        assertThatThrownBy(() -> authService.validateAssignableInspector(100L, 200L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_INSPECTOR));
    }

    @Test
    void validateAssignableInspector_다른회사소속_AUTH_INVALID_INSPECTOR() {
        User requester = requesterOf(1L);
        User assignee = assigneeOf(2L, Role.INSPECTOR, false);
        when(userRepository.findById(100L)).thenReturn(Optional.of(requester));
        when(userRepository.findById(200L)).thenReturn(Optional.of(assignee));

        assertThatThrownBy(() -> authService.validateAssignableInspector(100L, 200L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_INSPECTOR));
    }

    @Test
    void validateAssignableInspector_요청자가회사소속없음_AUTH_INVALID_INSPECTOR() {
        // requester.getCompanyId()==null 이면 sameCompany 판정이 단락 평가로 끝나 assignee.getCompanyId()까지
        // 가지 않는다 — assignee 쪽 companyId 스텁은 생략.
        User requester = requesterOf(null);
        User assignee = mock(User.class);
        when(assignee.getRole()).thenReturn(Role.INSPECTOR);
        when(assignee.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(userRepository.findById(100L)).thenReturn(Optional.of(requester));
        when(userRepository.findById(200L)).thenReturn(Optional.of(assignee));

        assertThatThrownBy(() -> authService.validateAssignableInspector(100L, 200L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_INSPECTOR));
    }
}
