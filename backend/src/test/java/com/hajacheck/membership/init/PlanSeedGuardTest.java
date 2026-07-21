package com.hajacheck.membership.init;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.repository.PlanRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * plans 시드 부팅 가드(#517/#518 P1) 단위 테스트 — enabled 토글·시드 존재 여부에 따른 기동 성공/실패.
 */
@ExtendWith(MockitoExtension.class)
class PlanSeedGuardTest {

    @Mock
    private PlanRepository planRepository;

    private PlanSeedGuard newGuard(boolean enabled) {
        PlanSeedGuard guard = new PlanSeedGuard(planRepository);
        ReflectionTestUtils.setField(guard, "enabled", enabled);
        return guard;
    }

    private Plan plan(PlanName name) {
        return Plan.create(name, 1, 50, 1, true, false, false, BigDecimal.ZERO);
    }

    @Test
    void run_활성화_시드3건모두존재하면_정상기동() {
        when(planRepository.findByName(any(PlanName.class)))
                .thenAnswer(inv -> Optional.of(plan(inv.getArgument(0))));

        assertThatCode(() -> newGuard(true).run(null)).doesNotThrowAnyException();
    }

    @Test
    void run_활성화_시드누락이면_IllegalStateException으로_기동실패() {
        when(planRepository.findByName(PlanName.FREE)).thenReturn(Optional.of(plan(PlanName.FREE)));
        when(planRepository.findByName(PlanName.STANDARD)).thenReturn(Optional.empty());
        when(planRepository.findByName(PlanName.ENTERPRISE)).thenReturn(Optional.of(plan(PlanName.ENTERPRISE)));

        assertThatThrownBy(() -> newGuard(true).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STANDARD");
    }

    @Test
    void run_비활성화면_조회없이_스킵() {
        newGuard(false).run(null);

        verify(planRepository, never()).findByName(any());
    }
}
