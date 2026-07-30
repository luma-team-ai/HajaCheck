package com.hajacheck.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * PlanTransitionService 단위 테스트 — 결제 승인 전이(#988)가 결제 주기를 새로 리셋하는지 고정한다
 * (#1104 / HAJA-525). 관리자 콘솔의 무결제 승계(AdminPlanServiceTest#플랜변경_기존결제주기가_신규구독에_승계된다)
 * 와 정확히 반대 규칙 — 여기서 승계로 바뀌면 "결제일이 밀리는" 원래 버그가 재발한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanTransitionServiceTest {

    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private UserPlanRepository userPlanRepository;
    @Mock
    private UsageCounterRepository usageCounterRepository;
    // #1105 — 결제 전이가 일어나면 그 구독에 걸린 하향 예약(PENDING)을 무효화한다(세 전이 경로 공통 규칙).
    @Mock
    private ScheduledPlanChangeCanceller scheduledPlanChangeCanceller;

    @InjectMocks
    private PlanTransitionService service;

    @Test
    void 결제승인전이_신규구독의_결제주기가_지금부터_1개월로_리셋된다() {
        Long companyId = 10L;
        UserPlan current = UserPlan.forCompany(companyId, 100L);
        // 기존 결제 주기가 이미 있었다는 걸 보여주기 위해 오래전 값을 심어 둔다 — 리셋되면 이 값과
        // 달라져야 한다(승계였다면 그대로 남았을 값).
        current.startNewBillingPeriod(Instant.parse("2020-01-01T00:00:00Z"));
        Plan targetPlan = Plan.create(PlanName.STANDARD, 10, 1000, 3, false, true, true,
                new BigDecimal("29000.00"));

        when(userPlanRepository.saveAndFlush(any(UserPlan.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Instant before = Instant.now();
        service.transitionTo(null, companyId, current, targetPlan);
        Instant after = Instant.now();

        ArgumentCaptor<UserPlan> saved = ArgumentCaptor.forClass(UserPlan.class);
        verify(userPlanRepository, times(2)).saveAndFlush(saved.capture());
        UserPlan renewed = saved.getAllValues().get(1);

        assertThat(renewed.getCurrentPeriodStart()).isNotNull();
        assertThat(renewed.getCurrentPeriodStart()).isBetween(before, after);
        assertThat(renewed.getCurrentPeriodEnd())
                .isAfter(renewed.getCurrentPeriodStart())
                // 1개월 리셋이므로 2020년 값과는 전혀 무관해야 한다(승계와 구분되는 핵심 단정).
                .isAfter(Instant.parse("2026-01-01T00:00:00Z"));
    }
}
