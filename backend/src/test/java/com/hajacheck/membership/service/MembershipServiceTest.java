package com.hajacheck.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.dto.MyPlanResponse;
import com.hajacheck.membership.dto.SeatsResponse;
import com.hajacheck.membership.dto.UpgradeInquiryResponse;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MembershipServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private UserPlanRepository userPlanRepository;
    @Mock
    private UsageCounterRepository usageCounterRepository;

    @InjectMocks
    private MembershipService service;

    private static final Long USER_ID = 1L;
    private static final Long COMPANY_ID = 10L;
    private static final Long PLAN_ID = 100L;

    private User individualUser;
    private User companyUser;
    private Plan standardPlan;

    @BeforeEach
    void setUp() {
        individualUser = user(USER_ID, null);
        companyUser = user(USER_ID, COMPANY_ID);
        standardPlan = Plan.create(PlanName.STANDARD, 10, 1000, 3, false, true, false,
                BigDecimal.valueOf(99000));
    }

    // ── getMyPlan ──

    @Test
    void 내플랜조회_개인구독_ACTIVE_사용량있음() {
        UserPlan userPlan = withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L);
        LocalDate period = YearMonth.now(ZoneId.of("Asia/Seoul")).atDay(1);
        UsageCounter usage = UsageCounter.create(500L, period, 786, 4, 12, 1, 0, 2);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(usageCounterRepository.findByUserPlanIdAndPeriod(500L, period)).thenReturn(Optional.of(usage));

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.plan().name()).isEqualTo("STANDARD");
        assertThat(response.plan().status()).isEqualTo("ACTIVE");
        assertThat(response.limits().maxFacilities()).isEqualTo(10);
        assertThat(response.limits().maxSeats()).isEqualTo(3);
        assertThat(response.usage().analyzedImageCount()).isEqualTo(786);
        assertThat(response.usage().facilityCount()).isEqualTo(4);
        assertThat(response.usage().period()).isEqualTo(period);
    }

    @Test
    void 내플랜조회_당월사용량행없음_0으로반환() {
        UserPlan userPlan = withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L);
        LocalDate period = YearMonth.now(ZoneId.of("Asia/Seoul")).atDay(1);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(usageCounterRepository.findByUserPlanIdAndPeriod(500L, period)).thenReturn(Optional.empty());

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.usage().facilityCount()).isZero();
        assertThat(response.usage().analyzedImageCount()).isZero();
        assertThat(response.usage().seatCount()).isZero();
        assertThat(response.usage().period()).isEqualTo(period);
    }

    @Test
    void 내플랜조회_ACTIVE없고_UPGRADE_REQUESTED만있으면_그대로반환() {
        UserPlan userPlan = withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L);
        userPlan.requestUpgrade();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.UPGRADE_REQUESTED))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(usageCounterRepository.findByUserPlanIdAndPeriod(eq(500L), any())).thenReturn(Optional.empty());

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.plan().status()).isEqualTo("UPGRADE_REQUESTED");
    }

    @Test
    void 내플랜조회_활성구독없음_PLAN_NOT_FOUND() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(eq(USER_ID), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyPlan(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    @Test
    void 내플랜조회_회사소속은_회사구독으로조회() {
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        LocalDate period = YearMonth.now(ZoneId.of("Asia/Seoul")).atDay(1);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(usageCounterRepository.findByUserPlanIdAndPeriod(501L, period)).thenReturn(Optional.empty());

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.plan().name()).isEqualTo("STANDARD");
    }

    // ── getSeats ──

    @Test
    void 좌석조회_회사소속_멤버목록과한도() {
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        User member2 = user(2L, COMPANY_ID);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(userRepository.countByCompanyIdAndStatus(COMPANY_ID, UserStatus.ACTIVE)).thenReturn(2L);
        when(userRepository.findByCompanyIdAndStatusOrderByIdAsc(eq(COMPANY_ID), eq(UserStatus.ACTIVE), any()))
                .thenReturn(List.of(companyUser, member2));

        SeatsResponse response = service.getSeats(USER_ID);

        assertThat(response.used()).isEqualTo(2);
        assertThat(response.limit()).isEqualTo(3);
        assertThat(response.members()).hasSize(2);
    }

    @Test
    void 좌석조회_정지된구성원은_제외() {
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        // findByCompanyIdAndStatusOrderByIdAsc(..., ACTIVE, ...) 자체가 정지 구성원을 제외한 결과를 반환한다는
        // 전제 — 리포지토리가 활성 사용자만 돌려주므로 서비스가 이를 그대로 신뢰함을 검증.
        when(userRepository.countByCompanyIdAndStatus(COMPANY_ID, UserStatus.ACTIVE)).thenReturn(1L);
        when(userRepository.findByCompanyIdAndStatusOrderByIdAsc(eq(COMPANY_ID), eq(UserStatus.ACTIVE), any()))
                .thenReturn(List.of(companyUser));

        SeatsResponse response = service.getSeats(USER_ID);

        assertThat(response.used()).isEqualTo(1);
        assertThat(response.members()).hasSize(1);
    }

    @Test
    void 좌석조회_상한초과시_목록은상한만_used는실제총원() {
        // 좌석 목록 조회 상한(#484) — members 목록은 방어적으로 잘리지만 used(총원 수) 는
        // 실제 활성 인원 수를 정확히 반영해야 한다(목록 truncation 이 used 집계를 왜곡하면 안 됨).
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        User member2 = user(2L, COMPANY_ID);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(userRepository.countByCompanyIdAndStatus(COMPANY_ID, UserStatus.ACTIVE)).thenReturn(250L);
        when(userRepository.findByCompanyIdAndStatusOrderByIdAsc(eq(COMPANY_ID), eq(UserStatus.ACTIVE), any()))
                .thenReturn(List.of(companyUser, member2));

        SeatsResponse response = service.getSeats(USER_ID);

        assertThat(response.used()).isEqualTo(250);
        assertThat(response.members()).hasSize(2);
    }

    @Test
    void 좌석조회_개인계정_본인1건() {
        UserPlan userPlan = withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));

        SeatsResponse response = service.getSeats(USER_ID);

        assertThat(response.used()).isEqualTo(1);
        assertThat(response.members()).hasSize(1);
        assertThat(response.members().get(0).userId()).isEqualTo(USER_ID);
    }

    @Test
    void 좌석조회_활성구독없음_PLAN_NOT_FOUND() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(eq(COMPANY_ID), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSeats(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    // ── requestUpgrade ──

    @Test
    void 업그레이드문의_개인구독_소유자본인_성공() {
        UserPlan userPlan = withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));

        UpgradeInquiryResponse response = service.requestUpgrade(USER_ID);

        assertThat(response.status()).isEqualTo("UPGRADE_REQUESTED");
        assertThat(userPlan.getStatus()).isEqualTo(UserPlanStatus.UPGRADE_REQUESTED);
    }

    @Test
    void 업그레이드문의_이미요청상태면_멱등_그대로200() {
        UserPlan userPlan = withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L);
        userPlan.requestUpgrade();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.UPGRADE_REQUESTED))
                .thenReturn(Optional.of(userPlan));

        UpgradeInquiryResponse response = service.requestUpgrade(USER_ID);

        assertThat(response.status()).isEqualTo("UPGRADE_REQUESTED");
    }

    @Test
    void 업그레이드문의_회사구독_소유자아니면_PLAN_FORBIDDEN() {
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        Company company = company(COMPANY_ID, 999L); // 다른 사람이 오너

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(companyRepository.findById(COMPANY_ID)).thenReturn(Optional.of(company));

        assertThatThrownBy(() -> service.requestUpgrade(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLAN_FORBIDDEN);
        assertThat(userPlan.getStatus()).isEqualTo(UserPlanStatus.ACTIVE);
    }

    @Test
    void 업그레이드문의_회사구독_소유자본인_성공() {
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        Company company = company(COMPANY_ID, USER_ID);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(companyRepository.findById(COMPANY_ID)).thenReturn(Optional.of(company));

        UpgradeInquiryResponse response = service.requestUpgrade(USER_ID);

        assertThat(response.status()).isEqualTo("UPGRADE_REQUESTED");
    }

    // ── fixtures ──

    private static User user(Long id, Long companyId) {
        User u = User.builder()
                .email("user" + id + "@haja.com")
                .name("사용자" + id)
                .role(Role.USER)
                .passwordHash("$2a$hashed")
                .companyId(companyId)
                .status(UserStatus.ACTIVE)
                .build();
        setId(u, id);
        return u;
    }

    private static Company company(Long id, Long ownerUserId) {
        Company c = Company.createPendingReview(ownerUserId, "(주)하자체크", "1234567890",
                "김민수", "서울시 강남구", null, "http://files/brn.png", "{}");
        setId(c, id);
        return c;
    }

    private static UserPlan withId(UserPlan userPlan, Long id) {
        setId(userPlan, id);
        return userPlan;
    }

    /** 테스트 전용 — IDENTITY 생성 id 를 리플렉션으로 세팅(엔티티에 setter 를 두지 않기 위함). */
    private static void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
