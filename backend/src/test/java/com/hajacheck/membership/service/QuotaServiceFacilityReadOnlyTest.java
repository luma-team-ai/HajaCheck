package com.hajacheck.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 시설물 읽기전용 <b>계산 판정</b>의 경계값 테스트(#890, 리뷰 P2-3).
 *
 * <p>이 한 칸 차이가 "정상 시설물이 갑자기 읽기전용이 되는" 지점이라 반드시 고정해야 한다 —
 * 순위(자기 자신 포함)가 한도와 <b>같으면 쓰기 가능</b>, <b>한도+1이면 읽기전용</b>이다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotaServiceFacilityReadOnlyTest {

    private static final Long COMPANY_ID = 10L;
    private static final Long FACILITY_ID = 77L;

    @Mock
    private UserPlanRepository userPlanRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private UsageCounterRepository usageCounterRepository;
    @Mock
    private PlanProvisioningService planProvisioningService;
    @Mock
    private FacilityRepository facilityRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PaymentGraceService paymentGraceService;
    @Spy
    private Clock clock = Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    @InjectMocks
    private QuotaService service;

    /**
     * 미결제 유예(#1177) 판정 — 이 테스트들은 유예와 무관하므로 <b>항상 구독 요금제 그대로</b>를
     * 돌려주도록 스텁한다(유예가 아닐 때의 실제 동작과 같다).
     */
    @BeforeEach
    void stubNoPaymentGrace() {
        when(paymentGraceService.resolveEffectivePlan(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    private void givenCompanyPlan(Integer maxFacilities) {
        UserPlan userPlan = UserPlan.forCompany(COMPANY_ID, 100L);
        ReflectionTestUtils.setField(userPlan, "id", 500L);
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(
                COMPANY_ID, UserPlanStatus.ACTIVE)).thenReturn(Optional.of(userPlan));
        when(planRepository.findById(anyLong())).thenReturn(Optional.of(
                Plan.create(PlanName.FREE, maxFacilities, 50, 1, true, false, false, BigDecimal.ZERO)));
    }

    @Test
    void 순위가_한도와_같으면_쓰기가능하다() {
        givenCompanyPlan(10);
        // 이 시설물이 회사에서 10번째(자기 자신 포함) → 한도 10 안에 든다.
        when(facilityRepository.countByCompanyIdAndIdLessThanEqual(COMPANY_ID, FACILITY_ID)).thenReturn(10L);

        assertThat(service.isFacilityReadOnly(COMPANY_ID, FACILITY_ID)).isFalse();
    }

    @Test
    void 순위가_한도를_한칸_넘으면_읽기전용이다() {
        givenCompanyPlan(10);
        when(facilityRepository.countByCompanyIdAndIdLessThanEqual(COMPANY_ID, FACILITY_ID)).thenReturn(11L);

        assertThat(service.isFacilityReadOnly(COMPANY_ID, FACILITY_ID)).isTrue();
    }

    @Test
    void 무제한플랜은_어떤_순위여도_쓰기가능하다() {
        givenCompanyPlan(null);
        when(facilityRepository.countByCompanyIdAndIdLessThanEqual(COMPANY_ID, FACILITY_ID)).thenReturn(9999L);

        assertThat(service.isFacilityReadOnly(COMPANY_ID, FACILITY_ID)).isFalse();
        // 무제한이면 순위 계산 자체가 불필요하다 — 쓸데없는 COUNT 를 돌지 않는지도 함께 고정한다.
        verify(facilityRepository, never()).countByCompanyIdAndIdLessThanEqual(anyLong(), anyLong());
    }

    @Test
    void 활성구독이_없으면_판정대상이_아니다() {
        // 회사 자원이 아닌 것에 회사 한도를 적용하지 않는다(fail-open) — reserveFacilitySlot 의
        // fail-closed 와 의도적으로 다른 정책이라 여기서 고정해 둔다.
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());

        assertThat(service.isFacilityReadOnly(COMPANY_ID, FACILITY_ID)).isFalse();
    }

    @Test
    void companyId나_facilityId가_없으면_판정하지_않는다() {
        assertThat(service.isFacilityReadOnly(null, FACILITY_ID)).isFalse();
        assertThat(service.isFacilityReadOnly(COMPANY_ID, null)).isFalse();
    }
}
