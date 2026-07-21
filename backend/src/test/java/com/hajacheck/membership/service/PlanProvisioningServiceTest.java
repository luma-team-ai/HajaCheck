package com.hajacheck.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 가입 시 FREE 플랜 자동 배정(#517) 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class PlanProvisioningServiceTest {

    @Mock
    private PlanRepository planRepository;

    @Mock
    private UserPlanRepository userPlanRepository;

    @InjectMocks
    private PlanProvisioningService service;

    private Plan freePlan() {
        return Plan.create(PlanName.FREE, 1, 50, 1, true, false, false, BigDecimal.ZERO);
    }

    @Test
    void ensureFreePlanForCompany_기존구독없으면_FREE배정() {
        Plan free = freePlan();
        // id 는 IDENTITY 생성이라 테스트에서 리플렉션 없이 직접 세팅 불가 — save 인자 캡처로 planId 만 검증.
        when(planRepository.findByName(PlanName.FREE)).thenReturn(Optional.of(free));
        when(userPlanRepository.existsByCompanyIdAndStatusIn(eq(10L), anyList())).thenReturn(false);

        service.ensureFreePlanForCompany(10L);

        ArgumentCaptor<UserPlan> captor = ArgumentCaptor.forClass(UserPlan.class);
        verify(userPlanRepository).save(captor.capture());
        assertThat(captor.getValue().getCompanyId()).isEqualTo(10L);
        assertThat(captor.getValue().getUserId()).isNull();
        assertThat(captor.getValue().getStatus()).isEqualTo(UserPlanStatus.ACTIVE);
    }

    @Test
    void ensureFreePlanForCompany_이미ACTIVE구독있으면_noop() {
        when(userPlanRepository.existsByCompanyIdAndStatusIn(eq(10L), anyList())).thenReturn(true);

        service.ensureFreePlanForCompany(10L);

        verify(userPlanRepository, never()).save(any());
        verify(planRepository, never()).findByName(any());
    }

    @Test
    void ensureFreePlanForUser_기존구독없으면_FREE배정() {
        Plan free = freePlan();
        when(planRepository.findByName(PlanName.FREE)).thenReturn(Optional.of(free));
        when(userPlanRepository.existsByUserIdAndStatusIn(eq(20L), anyList())).thenReturn(false);

        service.ensureFreePlanForUser(20L);

        ArgumentCaptor<UserPlan> captor = ArgumentCaptor.forClass(UserPlan.class);
        verify(userPlanRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(20L);
        assertThat(captor.getValue().getCompanyId()).isNull();
    }

    @Test
    void ensureFreePlanForUser_이미UPGRADE_REQUESTED있으면_noop() {
        when(userPlanRepository.existsByUserIdAndStatusIn(eq(20L), anyList())).thenReturn(true);

        service.ensureFreePlanForUser(20L);

        verify(userPlanRepository, never()).save(any());
    }

    @Test
    void ensureFreePlanForUser_FREE시드없으면_PLAN_DATA_INVALID() {
        when(userPlanRepository.existsByUserIdAndStatusIn(eq(30L), anyList())).thenReturn(false);
        when(planRepository.findByName(PlanName.FREE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ensureFreePlanForUser(30L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_DATA_INVALID));

        verify(userPlanRepository, never()).save(any());
    }

    @Test
    void ensureFreePlanForCompany_FREE시드없으면_PLAN_DATA_INVALID() {
        when(userPlanRepository.existsByCompanyIdAndStatusIn(eq(40L), anyList())).thenReturn(false);
        when(planRepository.findByName(PlanName.FREE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ensureFreePlanForCompany(40L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_DATA_INVALID));

        verify(userPlanRepository, never()).save(any());
    }
}
