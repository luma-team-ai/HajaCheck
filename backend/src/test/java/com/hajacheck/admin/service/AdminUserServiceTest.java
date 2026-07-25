package com.hajacheck.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hajacheck.admin.repository.AdminUserRepository;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * AdminUserService 단위 테스트 — changeStatus의 상태 화이트리스트 가드(#794, PR머신 리뷰 P3)를 고정한다.
 * WAITING은 companyId=null 소셜 가입 전용 자동 상태라 관리자가 임의로 부여할 수 없다(불변식
 * "WAITING=companyId 없음" 보호). 실제로는 WAITING 사용자가 companyId=null이라 회사 스코프
 * findByIdAndCompanyId로 애초에 조회되지 않는 방어 심층이지만, 화이트리스트 자체가 회귀 없이
 * 유지되는지 별도로 고정해둔다(향후 UserStatus 값 추가 시 무단 허용을 막기 위함).
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private AdminUserRepository adminUserRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AdminUserService adminUserService;

    @Test
    void changeStatus_WAITING_부여시도는_ADMIN_STATUS_NOT_ASSIGNABLE이고_조회하지않는다() {
        assertThatThrownBy(() -> adminUserService.changeStatus(1L, UserStatus.WAITING, 10L, 99L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_STATUS_NOT_ASSIGNABLE));

        verify(adminUserRepository, never()).findByIdAndCompanyId(anyLong(), anyLong());
        verify(adminUserRepository, never()).countByCompanyIdAndRoleAndStatus(
                anyLong(), any(Role.class), any(UserStatus.class));
    }
}
