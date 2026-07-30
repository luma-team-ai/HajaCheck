package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CompanyScopeGuardTest {

    private static final Long USER_ID = 101L;
    private static final Long COMPANY_ID = 202L;

    @Mock
    private CompanyMembershipRepository companyMembershipRepository;

    private CompanyScopeGuard companyScopeGuard;

    @BeforeEach
    void setUp() {
        companyScopeGuard = new CompanyScopeGuard(companyMembershipRepository);
    }

    @Test
    void 유효한현재소속이면_통과한다() {
        when(companyMembershipRepository.existsEffectiveApprovedMembership(
                org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                any(Instant.class)))
                .thenReturn(true);

        companyScopeGuard.requireEffectiveMembership(USER_ID, COMPANY_ID);

        verify(companyMembershipRepository).existsEffectiveApprovedMembership(
                org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                any(Instant.class));
    }

    @Test
    void 현재유효소속이아니면_FORBIDDEN이다() {
        when(companyMembershipRepository.existsEffectiveApprovedMembership(
                org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                any(Instant.class)))
                .thenReturn(false);

        assertForbidden(() -> companyScopeGuard.requireEffectiveMembership(USER_ID, COMPANY_ID));
    }

    @Test
    void principal회사Id가없으면_조회없이_FORBIDDEN이다() {
        assertForbidden(() -> companyScopeGuard.requireEffectiveMembership(USER_ID, null));
        verify(companyMembershipRepository, never())
                .existsEffectiveApprovedMembership(any(), any(), any());
    }

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }
}
