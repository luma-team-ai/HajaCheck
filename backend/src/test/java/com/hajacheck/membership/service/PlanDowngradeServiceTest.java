package com.hajacheck.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.membership.dto.DowngradeOverflow;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 플랜 하향 시 정지 대상 산출 규칙(#890) 단위 테스트.
 *
 * <p>여기서 고정하는 계약은 세 가지다: <b>owner 는 절대 정지되지 않는다</b>(정지되면 회사가 관리 불능),
 * <b>id 오름차순으로 오래된 계정을 남긴다</b>(규칙이 예측 가능해야 관리자가 결과를 미리 알 수 있다),
 * <b>무제한 플랜은 정지 대상이 없다</b>.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanDowngradeServiceTest {

    private static final Long COMPANY_ID = 10L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private FacilityRepository facilityRepository;

    @InjectMocks
    private PlanDowngradeService service;

    private static Plan plan(PlanName name, Integer maxFacilities, Integer maxSeats) {
        return Plan.create(name, maxFacilities, 50, maxSeats, true, false, false, BigDecimal.ZERO);
    }

    /** id 를 직접 지정해야 "오름차순으로 앞을 남긴다"를 검증할 수 있다(빌더에 id 세터가 없다). */
    private static User member(long id) {
        User user = User.builder()
                .companyId(COMPANY_ID)
                .email("member" + id + "@haja.test")
                .name("구성원" + id)
                .passwordHash("hash")
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private void givenActiveMembers(User... members) {
        when(userRepository.findByCompanyIdAndStatusOrderByIdAsc(
                eq(COMPANY_ID), eq(UserStatus.ACTIVE), any(Pageable.class)))
                .thenReturn(List.of(members));
    }

    private void givenOwner(long ownerUserId) {
        Company company = Company.createPendingReview(ownerUserId, "회사", "123-45-67890",
                "대표", "주소", null, "url", "{\"source\":\"MANUAL_INPUT\"}");
        when(companyRepository.findById(COMPANY_ID)).thenReturn(Optional.of(company));
    }

    @Test
    void FREE_1석으로_내리면_owner만_남고_나머지가_정지대상이된다() {
        givenActiveMembers(member(1L), member(5L), member(9L));
        givenOwner(5L);
        when(facilityRepository.countByCompanyId(anyLong())).thenReturn(0L);

        DowngradeOverflow overflow = service.preview(COMPANY_ID, plan(PlanName.FREE, 1, 1));

        // owner(5) 는 id 가 가장 작지 않아도 무조건 유지된다 — 정지되면 회사가 관리 불능이 된다.
        assertThat(overflow.seatUserIdsToSuspend()).containsExactly(1L, 9L);
        assertThat(overflow.exists()).isTrue();
    }

    @Test
    void 한도가_남으면_owner다음으로_id오름차순_앞쪽을_남긴다() {
        givenActiveMembers(member(1L), member(5L), member(9L), member(12L));
        givenOwner(9L);
        when(facilityRepository.countByCompanyId(anyLong())).thenReturn(0L);

        DowngradeOverflow overflow = service.preview(COMPANY_ID, plan(PlanName.STANDARD, 10, 3));

        // 3석: owner(9) + id 오름차순 앞의 1, 5 → 12 만 정지.
        assertThat(overflow.seatUserIdsToSuspend()).containsExactly(12L);
    }

    @Test
    void 현재인원이_한도이하면_정지대상이없다() {
        givenActiveMembers(member(1L), member(2L));
        givenOwner(1L);
        when(facilityRepository.countByCompanyId(anyLong())).thenReturn(0L);

        DowngradeOverflow overflow = service.preview(COMPANY_ID, plan(PlanName.STANDARD, 10, 3));

        assertThat(overflow.seatUserIdsToSuspend()).isEmpty();
        assertThat(overflow.exists()).isFalse();
    }

    @Test
    void 무제한플랜은_좌석도_시설물도_초과가없다() {
        givenActiveMembers(member(1L), member(2L), member(3L));
        givenOwner(1L);
        when(facilityRepository.countByCompanyId(anyLong())).thenReturn(99L);

        DowngradeOverflow overflow = service.preview(COMPANY_ID, plan(PlanName.ENTERPRISE, null, null));

        assertThat(overflow.exists()).isFalse();
        assertThat(overflow.facilityOverflowCount()).isZero();
    }

    @Test
    void 시설물_초과분은_한도를_넘는_개수만큼_집계된다() {
        givenActiveMembers(member(1L));
        givenOwner(1L);
        when(facilityRepository.countByCompanyId(COMPANY_ID)).thenReturn(11L);

        DowngradeOverflow overflow = service.preview(COMPANY_ID, plan(PlanName.FREE, 1, 1));

        // 11개 보유 + FREE 1개 한도 → 10개가 읽기 전용으로 전환된다(삭제하지 않는다).
        assertThat(overflow.facilityOverflowCount()).isEqualTo(10);
        assertThat(overflow.exists()).isTrue();
    }

    @Test
    void 정지_적용은_대상만_SUSPENDED로_바꾸고_owner는_ACTIVE로_남긴다() {
        User owner = member(5L);
        User other = member(9L);
        givenActiveMembers(member(1L), owner, other);
        givenOwner(5L);
        when(facilityRepository.countByCompanyId(anyLong())).thenReturn(0L);
        when(userRepository.findAllById(any())).thenReturn(List.of(member(1L), other));

        DowngradeOverflow applied = service.applyOverflow(COMPANY_ID, plan(PlanName.FREE, 1, 1));

        assertThat(applied.seatUserIdsToSuspend()).containsExactly(1L, 9L);
        assertThat(other.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(owner.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }
}
