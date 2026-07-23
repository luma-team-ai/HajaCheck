package com.hajacheck.core.defect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.support.RateLimiter;
import com.hajacheck.core.ai.config.AiServerProperties;
import com.hajacheck.core.ai.support.AiProxyRateLimiter;
import com.hajacheck.core.defect.dto.NlSearchResult;
import com.hajacheck.support.InMemoryRateLimiter;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * NlSearchService 단위테스트 — 플랜/멤버십 게이트는 Mockito, FastAPI 호출은 MockRestServiceServer로
 * 검증(AiProxyServiceTest·MembershipServiceTest 패턴 결합, HAJA-120).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NlSearchServiceTest {

    private static final String AI_SERVER_URL = "http://ai-server-test/ai/nl-search";
    private static final Long USER_ID = 1L;
    private static final Long COMPANY_ID = 10L;
    private static final Long PLAN_ID = 100L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserPlanRepository userPlanRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private CompanyMembershipRepository companyMembershipRepository;

    private MockRestServiceServer mockServer;
    private RestClient.Builder builder;
    private AiServerProperties properties;
    private NlSearchService service;

    private User individualUser;
    private User companyUser;
    private Plan addonPlan;
    private Plan noAddonPlan;

    @BeforeEach
    void setUp() {
        individualUser = user(USER_ID, null);
        companyUser = user(USER_ID, COMPANY_ID);
        addonPlan = Plan.create(PlanName.STANDARD, 10, 1000, 3, false, true, true, BigDecimal.valueOf(99000));
        noAddonPlan = Plan.create(PlanName.FREE, 3, 10, 1, true, false, false, BigDecimal.ZERO);

        properties = new AiServerProperties();
        properties.setBaseUrl("http://ai-server-test");
        properties.setInternalServiceToken("test-service-token");
        properties.setConnectTimeoutMs(3000);
        properties.setReadTimeoutMs(60000);

        builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        mockServer = MockRestServiceServer.bindTo(builder).build();
        // 실 구현(RedisRateLimiter)은 @Profile("!test")라 in-memory fake 로 대체(한도 내 통과).
        service = newService(new InMemoryRateLimiter());
    }

    private NlSearchService newService(RateLimiter rateLimiter) {
        return new NlSearchService(builder.build(), properties, userRepository, userPlanRepository,
                planRepository, companyMembershipRepository, new AiProxyRateLimiter(rateLimiter));
    }

    // ── 성공 경로 ──

    @Test
    void 검색_개인활성플랜_addon있음_성공_내부토큰헤더부착() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L)));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(addonPlan));

        mockServer.expect(requestTo(AI_SERVER_URL))
                .andExpect(header("X-Internal-Service-Token", "test-service-token"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"data":{"filters":{"type":[],"grade":["D","E"],"status":[],"confidenceMin":null},"unsupported_terms":[],"clarifying_question":null,"interpretation_confidence":0.9}}
                                """));

        ApiResponse<NlSearchResult> response = service.search(USER_ID, "D등급 이상 하자");

        assertThat(response.success()).isTrue();
        assertThat(response.data().filters().grade()).containsExactly("D", "E");
        mockServer.verify();
    }

    @Test
    void 검색_회사소속_유효멤버십_addon있음_성공() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(companyMembershipRepository.existsEffectiveApprovedMembership(
                ArgumentMatchers.eq(COMPANY_ID), ArgumentMatchers.eq(USER_ID), any(Instant.class)))
                .thenReturn(true);
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L)));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(addonPlan));

        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"data":{"filters":{"type":["CRACK"],"grade":[],"status":[],"confidenceMin":null},"unsupported_terms":[],"clarifying_question":null,"interpretation_confidence":0.9}}
                                """));

        ApiResponse<NlSearchResult> response = service.search(USER_ID, "균열만 보여줘");

        assertThat(response.success()).isTrue();
        mockServer.verify();
    }

    // ── 게이트 실패(FastAPI 호출 없음) ──

    @Test
    void 검색_회사소속_유효멤버십없음_AI_ADDON_REQUIRED_내부호출없음() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(companyMembershipRepository.existsEffectiveApprovedMembership(
                ArgumentMatchers.eq(COMPANY_ID), ArgumentMatchers.eq(USER_ID), any(Instant.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> service.search(USER_ID, "균열만 보여줘"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_ADDON_REQUIRED);
        mockServer.verify(); // 설정된 기대치 없음 = 어떤 요청도 발생하지 않아야 통과
    }

    @Test
    void 검색_개인_활성플랜없음_AI_ADDON_REQUIRED_내부호출없음() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.search(USER_ID, "균열만 보여줘"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_ADDON_REQUIRED);
        mockServer.verify();
    }

    @Test
    void 검색_활성플랜은있으나_참조Plan행없음_PLAN_DATA_INVALID_내부호출없음() {
        // 리뷰 P3: "플랜 없음"(AI_ADDON_REQUIRED)과 FK 정합성 깨짐(PLAN_DATA_INVALID)을 구분.
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L)));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.search(USER_ID, "균열만 보여줘"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLAN_DATA_INVALID);
        mockServer.verify();
    }

    @Test
    void 검색_개인_활성플랜있으나_addon없음_AI_ADDON_REQUIRED_내부호출없음() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L)));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(noAddonPlan));

        assertThatThrownBy(() -> service.search(USER_ID, "균열만 보여줘"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_ADDON_REQUIRED);
        mockServer.verify();
    }

    @Test
    void 검색_빈질의_INVALID_INPUT_내부호출없음_게이트조회도안함() {
        assertThatThrownBy(() -> service.search(USER_ID, "   "))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        mockServer.verify();
        Mockito.verifyNoInteractions(userRepository);
    }

    @Test
    void 검색_500자초과_INVALID_INPUT_내부호출없음() {
        String tooLong = "가".repeat(501);

        assertThatThrownBy(() -> service.search(USER_ID, tooLong))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        mockServer.verify();
    }

    // ── rate-limit 가드(스레드풀 보호, #582 Critical) ──

    @Test
    void 검색_rate_limit초과_AUTH_TOO_MANY_REQUESTS_내부호출없음() {
        // 플랜 게이트는 통과시키고(rate-limit 은 requireAiAddon 이후에 적용된다), rate-limiter 만 거부하게 해
        // 429 가 던져지고 그 뒤 FastAPI 호출이 발생하지 않음을 검증한다.
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L)));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(addonPlan));

        NlSearchService limited = newService((key, limit, window) -> false); // 항상 초과(거부)

        assertThatThrownBy(() -> limited.search(USER_ID, "균열만 보여줘"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        mockServer.verify(); // 기대치 없음 = 어떤 FastAPI 요청도 발생하지 않아야 통과
    }

    // ── FastAPI 응답 전파/장애 ──

    @Test
    void 검색_LLM실패_에러코드메시지그대로전파() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L)));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(addonPlan));

        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":false,"error":{"code":"LLM_TIMEOUT","message":"응답 시간 초과"}}
                                """));

        ApiResponse<NlSearchResult> response = service.search(USER_ID, "균열만 보여줘");

        assertThat(response.success()).isFalse();
        assertThat(response.error().code()).isEqualTo("LLM_TIMEOUT");
    }

    @Test
    void 검색_연결불가_AI_SERVER_UNREACHABLE예외() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L)));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(addonPlan));

        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(request -> {
                    throw new ConnectException("Connection refused");
                });

        assertThatThrownBy(() -> service.search(USER_ID, "균열만 보여줘"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_SERVER_UNREACHABLE);
    }

    // ── fixtures ──

    private static User user(Long id, Long companyId) {
        User u = User.builder()
                .email("user" + id + "@haja.com")
                .name("점검자" + id)
                .role(Role.INSPECTOR)
                .passwordHash("$2a$hashed")
                .companyId(companyId)
                .status(UserStatus.ACTIVE)
                .build();
        setId(u, id);
        return u;
    }

    private static UserPlan withId(UserPlan userPlan, Long id) {
        setId(userPlan, id);
        return userPlan;
    }

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
