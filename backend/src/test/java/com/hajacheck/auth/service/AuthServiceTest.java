package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
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
 * validateAssignableInspector 단위 테스트(dev-05-02) — 요청자·배정자의 상태, 역할, 소속 회사를 검증.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CompanyMembershipRepository companyMembershipRepository;

    @InjectMocks
    private AuthService authService;

    private static User requesterOf(Long companyId) {
        return requesterOf(companyId, UserStatus.ACTIVE);
    }

    private static User requesterOf(Long companyId, UserStatus status) {
        User user = mock(User.class);
        when(user.getCompanyId()).thenReturn(companyId);
        when(user.getStatus()).thenReturn(status);
        return user;
    }

    private static User assigneeOf(Long companyId, Role role, boolean suspended) {
        User user = mock(User.class);
        when(user.getCompanyId()).thenReturn(companyId);
        when(user.getRole()).thenReturn(role);
        when(user.getStatus()).thenReturn(suspended ? UserStatus.SUSPENDED : UserStatus.ACTIVE);
        return user;
    }

    private void givenEffectiveMemberships(Long companyId, Long requesterUserId, Long assignedInspectorId) {
        when(companyMembershipRepository.existsEffectiveApprovedMembership(
                org.mockito.ArgumentMatchers.eq(companyId),
                org.mockito.ArgumentMatchers.eq(requesterUserId),
                any())).thenReturn(true);
        when(companyMembershipRepository.existsEffectiveApprovedMembership(
                org.mockito.ArgumentMatchers.eq(companyId),
                org.mockito.ArgumentMatchers.eq(assignedInspectorId),
                any())).thenReturn(true);
    }

    @Test
    void validateAssignableInspector_같은회사_INSPECTOR_ACTIVE_통과() {
        User requester = requesterOf(1L);
        User assignee = assigneeOf(1L, Role.INSPECTOR, false);
        when(userRepository.findById(100L)).thenReturn(Optional.of(requester));
        when(userRepository.findById(200L)).thenReturn(Optional.of(assignee));
        givenEffectiveMemberships(1L, 100L, 200L);

        authService.validateAssignableInspector(100L, 200L);
    }

    @Test
    void validateAssignableInspector_같은회사_ADMIN_ACTIVE_통과() {
        User requester = requesterOf(1L);
        User assignee = assigneeOf(1L, Role.ADMIN, false);
        when(userRepository.findById(100L)).thenReturn(Optional.of(requester));
        when(userRepository.findById(200L)).thenReturn(Optional.of(assignee));
        givenEffectiveMemberships(1L, 100L, 200L);

        authService.validateAssignableInspector(100L, 200L);
    }

    @Test
    void validateAssignableInspector_배정자멤버십회수또는만료_AUTH_INVALID_INSPECTOR() {
        User requester = requesterOf(1L);
        User assignee = assigneeOf(1L, Role.INSPECTOR, false);
        when(userRepository.findById(100L)).thenReturn(Optional.of(requester));
        when(userRepository.findById(200L)).thenReturn(Optional.of(assignee));
        when(companyMembershipRepository.existsEffectiveApprovedMembership(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(100L),
                any())).thenReturn(true);
        when(companyMembershipRepository.existsEffectiveApprovedMembership(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(200L),
                any())).thenReturn(false);

        assertThatThrownBy(() -> authService.validateAssignableInspector(100L, 200L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_INSPECTOR));
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
    void validateAssignableInspector_정지된요청자와양쪽유효멤버십_AUTH_INVALID_INSPECTOR() {
        User requester = requesterOf(1L, UserStatus.SUSPENDED);
        User assignee = mock(User.class);
        when(assignee.getCompanyId()).thenReturn(1L);
        when(assignee.getRole()).thenReturn(Role.INSPECTOR);
        lenient().when(assignee.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(userRepository.findById(100L)).thenReturn(Optional.of(requester));
        when(userRepository.findById(200L)).thenReturn(Optional.of(assignee));
        // 회귀 전 구현에서는 양쪽 멤버십이 유효하면 통과했다. 수정 후에는 상태 검사에서 먼저 거부되므로
        // 해당 스텁이 사용되지 않을 수 있음을 명시한다.
        lenient().when(companyMembershipRepository.existsEffectiveApprovedMembership(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(100L),
                any())).thenReturn(true);
        lenient().when(companyMembershipRepository.existsEffectiveApprovedMembership(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(200L),
                any())).thenReturn(true);

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
        // 가지 않는다 — assignee 쪽 companyId 스텁은 생략. 요청자 상태는 ACTIVE여야 소속 검사까지 도달한다.
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

    @Test
    void validateAssignableInspector_반려회사멤버십_AUTH_INVALID_INSPECTOR() {
        User requester = requesterOf(1L);
        User assignee = assigneeOf(1L, Role.INSPECTOR, false);
        when(userRepository.findById(100L)).thenReturn(Optional.of(requester));
        when(userRepository.findById(200L)).thenReturn(Optional.of(assignee));
        when(companyMembershipRepository.existsEffectiveApprovedMembership(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(100L),
                any())).thenReturn(false);

        assertThatThrownBy(() -> authService.validateAssignableInspector(100L, 200L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_INSPECTOR));
    }

    @Test
    void validateAssignableInspector_미검증회사멤버십_AUTH_INVALID_INSPECTOR() {
        User requester = requesterOf(1L);
        User assignee = assigneeOf(1L, Role.INSPECTOR, false);
        when(userRepository.findById(100L)).thenReturn(Optional.of(requester));
        when(userRepository.findById(200L)).thenReturn(Optional.of(assignee));
        when(companyMembershipRepository.existsEffectiveApprovedMembership(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(100L),
                any())).thenReturn(false);

        assertThatThrownBy(() -> authService.validateAssignableInspector(100L, 200L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_INSPECTOR));
    }
}
