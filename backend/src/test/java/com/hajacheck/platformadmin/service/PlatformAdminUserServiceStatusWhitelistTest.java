package com.hajacheck.platformadmin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.config.DemoProperties;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
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
 * 플랫폼 관리자 콘솔의 계정 상태 화이트리스트(#1492 리뷰 ⓐ) — {@code AdminUserService} 에만 있던
 * {@code requireAssignableStatus} 가드가 이 경로에도 걸리는지 고정한다.
 *
 * <p><b>왜 중요한가</b>: {@code AdminUserStatusUpdateRequest} 는 {@code @NotNull UserStatus} 만 걸려
 * 있어 {@code {"status":"WAITING"}} 이 그대로 서비스까지 들어온다. 회사에 소속된 사용자가 WAITING 으로
 * 되돌려지면 <b>{@code company_id} 가 남은 WAITING 행</b>이 생겨,
 * {@code UserRepository#findByIdForUpdate} 의 교착 안전성 근거("WAITING ⇒ {@code company_id IS NULL}")가
 * 깨진다. 회사 콘솔은 이미 막고 있었으므로({@code AdminUserServiceTest}) 여기만 열려 있으면 같은 가드가
 * 플랫폼 관리자 경로로 그대로 우회된다.
 */
class PlatformAdminUserServiceStatusWhitelistTest {

    private static final long TARGET_USER_ID = 11L;

    private PlatformAdminUserRepository platformAdminUserRepository;
    private PlatformAdminUserService service;
    private User target;

    @BeforeEach
    void setUp() {
        platformAdminUserRepository = mock(PlatformAdminUserRepository.class);
        DemoProperties demoProperties = new DemoProperties();
        demoProperties.setLoginId("demo-admin@hajacheck.demo");
        service = new PlatformAdminUserService(platformAdminUserRepository, mock(CompanyRepository.class),
                mock(PasswordEncoder.class), mock(QuotaService.class), mock(CounselorSkillRepository.class),
                new DemoAccountGuard(demoProperties, mock(UserRepository.class)));

        target = mock(User.class);
        when(target.getEmail()).thenReturn("member@haja.test");
        // ADMIN 이 아니어야 requireNotLastCompanyAdmin(회사 행 잠금) 분기를 타지 않는다 — 여기서
        // 검증하려는 것은 화이트리스트 가드 하나뿐이다.
        when(target.getRole()).thenReturn(Role.USER);
    }

    @Test
    void changeStatus_WAITING_부여시도는_ADMIN_STATUS_NOT_ASSIGNABLE이고_조회하지않는다() {
        assertThatThrownBy(() -> service.changeStatus(TARGET_USER_ID, UserStatus.WAITING))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ADMIN_STATUS_NOT_ASSIGNABLE);

        // 대상 조회보다 먼저 막아야 잘못된 상태 요청이 리소스 존재 여부 탐지 수단이 되지 않는다
        // (AdminUserServiceTest 의 동일 케이스와 같은 계약).
        // ⚠️ findById 가 아니라 findByIdForUpdate 다(#1492 P2) — changeStatus 의 로드가 잠금 조회로
        // 바뀌었으므로, findById 로 단언하면 프로덕션이 그 메서드를 아예 호출하지 않아 **항상 통과하는
        // 공허한 단언**이 되어 이 계약이 조용히 사라진다.
        verify(platformAdminUserRepository, never()).findByIdForUpdate(anyLong());
        verify(target, never()).changeStatus(UserStatus.WAITING);
    }

    @Test
    void changeStatus_ACTIVE_SUSPENDED는_화이트리스트를_통과한다() {
        // changeStatus 는 잠금 조회로 로드한다(#1492 P2, PlatformAdminUserService#findUserForUpdate).
        when(platformAdminUserRepository.findByIdForUpdate(TARGET_USER_ID)).thenReturn(Optional.of(target));
        when(target.getId()).thenReturn(TARGET_USER_ID);
        when(target.getStatus()).thenReturn(UserStatus.SUSPENDED, UserStatus.ACTIVE);

        assertThat(service.changeStatus(TARGET_USER_ID, UserStatus.SUSPENDED).status())
                .isEqualTo(UserStatus.SUSPENDED);
        assertThat(service.changeStatus(TARGET_USER_ID, UserStatus.ACTIVE).status())
                .isEqualTo(UserStatus.ACTIVE);

        verify(target).changeStatus(UserStatus.SUSPENDED);
        verify(target).changeStatus(UserStatus.ACTIVE);
    }
}
