package com.hajacheck.platformadmin.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.config.DemoProperties;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.service.DemoAccountGuard;
import com.hajacheck.counsel.repository.CounselorSkillRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.service.QuotaService;
import com.hajacheck.platformadmin.repository.PlatformAdminUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 데모 계정 자기보호(#1626) — 플랫폼 관리자 콘솔 경로도 회사 콘솔과 동일하게 차단됨을 고정한다.
 * 이 경로가 열려 있으면 {@code AdminUserService} 가드가 그대로 우회된다(우회로 봉쇄 검증).
 */
class PlatformAdminUserServiceDemoGuardTest {

    private static final String DEMO_LOGIN_ID = "demo-admin@hajacheck.demo";
    private static final long DEMO_USER_ID = 7L;

    private PlatformAdminUserRepository platformAdminUserRepository;
    private PlatformAdminUserService service;
    private User demoUser;

    @BeforeEach
    void setUp() {
        platformAdminUserRepository = mock(PlatformAdminUserRepository.class);
        DemoProperties demoProperties = new DemoProperties();
        demoProperties.setLoginId(DEMO_LOGIN_ID);
        service = new PlatformAdminUserService(platformAdminUserRepository, mock(CompanyRepository.class),
                mock(PasswordEncoder.class), mock(QuotaService.class), mock(CounselorSkillRepository.class),
                new DemoAccountGuard(demoProperties, org.mockito.Mockito.mock(com.hajacheck.auth.repository.UserRepository.class)));

        demoUser = mock(User.class);
        when(demoUser.getEmail()).thenReturn(DEMO_LOGIN_ID);
        // findUser 의 PLATFORM_ADMIN 숨김 분기를 지나도록 일반 role — 데모 계정은 기업 ADMIN 이다.
        when(demoUser.getRole()).thenReturn(Role.ADMIN);
        // changeRole·changeStatus 는 잠금 조회로 로드한다(#1492 P2,
        // PlatformAdminUserService#findUserForUpdate) — findById 스텁은 더 이상 걸리지 않는다.
        when(platformAdminUserRepository.findByIdForUpdate(DEMO_USER_ID)).thenReturn(Optional.of(demoUser));
    }

    @Test
    void changeRole_데모계정대상_409이고_역할이_바뀌지_않는다() {
        assertThatThrownBy(() -> service.changeRole(DEMO_USER_ID, Role.USER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DEMO_ACCOUNT_PROTECTED);

        verify(demoUser, never()).changeRole(any());
    }

    @Test
    void changeStatus_데모계정대상_409이고_상태가_바뀌지_않는다() {
        assertThatThrownBy(() -> service.changeStatus(DEMO_USER_ID, UserStatus.SUSPENDED))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DEMO_ACCOUNT_PROTECTED);

        verify(demoUser, never()).changeStatus(any());
    }
}
