package com.hajacheck.admin.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.admin.repository.AdminUserRepository;
import com.hajacheck.auth.config.DemoProperties;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.service.DemoAccountGuard;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.service.QuotaService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 데모 계정 자기보호(#1626) — 회사 관리자 콘솔의 role/status 변경이 데모 계정을 대상으로 하면
 * 409(DEMO_ACCOUNT_PROTECTED)로 차단되고 <b>어떤 상태 변경도 일어나지 않음</b>을 고정한다.
 * 가드는 mock 이 아니라 실제 {@link DemoAccountGuard}(설정 매칭)를 쓴다 — 판별 규칙까지 함께 검증.
 */
class AdminUserServiceDemoGuardTest {

    private static final String DEMO_LOGIN_ID = "demo-admin@hajacheck.demo";
    private static final long DEMO_USER_ID = 7L;
    private static final long COMPANY_ID = 10L;
    private static final long REQUESTER_ID = 99L;

    private AdminUserRepository adminUserRepository;
    private AdminUserService service;
    private User demoUser;

    @BeforeEach
    void setUp() {
        adminUserRepository = mock(AdminUserRepository.class);
        DemoProperties demoProperties = new DemoProperties();
        demoProperties.setLoginId(DEMO_LOGIN_ID);
        service = new AdminUserService(adminUserRepository, mock(PasswordEncoder.class),
                mock(QuotaService.class), new DemoAccountGuard(demoProperties));

        demoUser = mock(User.class);
        when(demoUser.getEmail()).thenReturn(DEMO_LOGIN_ID);
        when(adminUserRepository.findByIdAndCompanyId(DEMO_USER_ID, COMPANY_ID))
                .thenReturn(Optional.of(demoUser));
    }

    @Test
    void changeRole_데모계정대상_409이고_역할이_바뀌지_않는다() {
        assertThatThrownBy(() -> service.changeRole(DEMO_USER_ID, Role.USER, COMPANY_ID, REQUESTER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DEMO_ACCOUNT_PROTECTED);

        verify(demoUser, never()).changeRole(any());
    }

    @Test
    void changeStatus_데모계정대상_409이고_상태가_바뀌지_않는다() {
        assertThatThrownBy(() -> service.changeStatus(DEMO_USER_ID, UserStatus.SUSPENDED, COMPANY_ID, REQUESTER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DEMO_ACCOUNT_PROTECTED);

        verify(demoUser, never()).changeStatus(any());
    }
}
