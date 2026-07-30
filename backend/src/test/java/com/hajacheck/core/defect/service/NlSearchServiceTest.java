package com.hajacheck.core.defect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
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
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.membership.service.PaymentGraceService;
import com.hajacheck.support.InMemoryRateLimiter;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-28T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserPlanRepository userPlanRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private CompanyMembershipRepository companyMembershipRepository;
    @Mock
    private PaymentGraceService paymentGraceService;

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
                planRepository, paymentGraceService, companyMembershipRepository,
                new AiProxyRateLimiter(rateLimiter), FIXED_CLOCK);
    }

    /**
     * 미결제 유예(#1177) — 이 테스트들은 유예와 무관하므로 <b>항상 구독 요금제 그대로</b>를 돌려주도록
     * 스텁한다(유예가 아닐 때의 실제 동작과 같다). 유예 중 차단은 별도 회귀 테스트가 검증한다.
     */
    @BeforeEach
    void stubNoPaymentGrace() {
        when(paymentGraceService.resolveEffectivePlan(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
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
                .andExpect(content().json("""
                        {"query":"D등급 이상 하자","referenceDate":"2026-07-28"}
                        """))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"data":{"filters":{"type":[],"grade":["D","E"],"status":[],"confidenceMin":null,"inspectionType":[],"inspectionStatus":[],"inspectionDateFrom":null,"inspectionDateTo":null,"roundNoMin":null,"roundNoMax":null,"defectCountMin":null,"defectCountMax":null},"unsupported_terms":[],"clarifying_question":null,"interpretation_confidence":0.9}}
                                """));

        ApiResponse<NlSearchResult> response = service.search(USER_ID, "D등급 이상 하자");

        assertThat(response.success()).isTrue();
        assertThat(response.data().filters().grade()).containsExactly("D", "E");
        mockServer.verify();
    }

    /**
     * 구버전 ai-server(HAJA-538 신규 8필드 미도입, nl_search_chain.py의 NlSearchFilters가 여전히
     * type/grade/status/confidenceMin 4필드만 반환) 응답과의 하위 호환 회귀 방지(PR #1155 리뷰 P1).
     * 신규 필드 키가 JSON에 아예 없어도(null 역직렬화) isValidResult()가 이를 "미지정"으로 수용해야
     * 기존 하자 자연어 검색(HAJA-120)이 계속 동작한다.
     */
    @Test
    void 검색_구버전AI응답_신규필터필드부재_성공() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L)));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(addonPlan));

        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"data":{"filters":{"type":[],"grade":["D","E"],"status":[],"confidenceMin":null},"unsupported_terms":[],"clarifying_question":null,"interpretation_confidence":0.9}}
                                """));

        ApiResponse<NlSearchResult> response = service.search(USER_ID, "D등급 이상 하자");

        assertThat(response.success()).isTrue();
        assertThat(response.data().filters().grade()).containsExactly("D", "E");
        assertThat(response.data().filters().inspectionType()).isNull();
        assertThat(response.data().filters().inspectionStatus()).isNull();
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
                                {"success":true,"data":{"filters":{"type":["CRACK"],"grade":[],"status":[],"confidenceMin":null,"inspectionType":[],"inspectionStatus":[],"inspectionDateFrom":null,"inspectionDateTo":null,"roundNoMin":null,"roundNoMax":null,"defectCountMin":null,"defectCountMax":null},"unsupported_terms":[],"clarifying_question":null,"interpretation_confidence":0.9}}
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
    void 검색_미결제유예중이면_AI_ADDON_REQUIRED_내부호출없음() {
        // #1177 리뷰 P1 회귀선 — 유료→유료 하향은 결제 없이 대상 요금제를 발급한다. 그 유예 구독의
        // plan_id 는 STANDARD(hasAiAddon=true)라 이 게이트가 원본 요금제를 그대로 읽으면 <b>AI
        // 부가기능을 무상으로</b> 쓸 수 있다. 이 판정은 QuotaService 를 거치지 않으므로 한도 3종을
        // 낮추는 것만으로는 막히지 않는다.
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L)));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(addonPlan));
        // 유예 중이면 엔타이틀먼트가 FREE 로 낮아진다(hasAiAddon=false).
        when(paymentGraceService.resolveEffectivePlan(any(), any())).thenReturn(noAddonPlan);

        assertThatThrownBy(() -> service.search(USER_ID, "균열만 보여줘"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_ADDON_REQUIRED);
        mockServer.verify(); // 설정된 기대치 없음 = FastAPI 로 어떤 요청도 나가지 않아야 통과
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

    @Test
    void 검색_AI가역전범위반환_AI_INVALID_RESPONSE() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L)));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(addonPlan));

        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"data":{"filters":{"type":[],"grade":[],"status":[],"confidenceMin":null,"inspectionType":["REGULAR"],"inspectionStatus":["REVIEWED"],"inspectionDateFrom":"2026-07-20","inspectionDateTo":"2026-07-01","roundNoMin":1,"roundNoMax":1,"defectCountMin":0,"defectCountMax":5},"unsupported_terms":[],"clarifying_question":null,"interpretation_confidence":0.9}}
                                """));

        assertThatThrownBy(() -> service.search(USER_ID, "지난 점검"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_INVALID_RESPONSE);
        mockServer.verify();
    }

    @Test
    void 검색_AI가중복하자Enum배열반환_AI_INVALID_RESPONSE() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L)));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(addonPlan));

        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"data":{"filters":{"type":["CRACK","CRACK"],"grade":[],"status":[],"confidenceMin":null,"inspectionType":[],"inspectionStatus":[],"inspectionDateFrom":null,"inspectionDateTo":null,"roundNoMin":null,"roundNoMax":null,"defectCountMin":null,"defectCountMax":null},"unsupported_terms":[],"clarifying_question":null,"interpretation_confidence":0.9}}
                                """));

        assertThatThrownBy(() -> service.search(USER_ID, "균열 점검"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_INVALID_RESPONSE);
        mockServer.verify();
    }

    @Test
    void 검색_AI가중복점검Enum배열반환_AI_INVALID_RESPONSE() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L)));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(addonPlan));

        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"data":{"filters":{"type":[],"grade":[],"status":[],"confidenceMin":null,"inspectionType":[],"inspectionStatus":["REVIEWED","REVIEWED"],"inspectionDateFrom":null,"inspectionDateTo":null,"roundNoMin":null,"roundNoMax":null,"defectCountMin":null,"defectCountMax":null},"unsupported_terms":[],"clarifying_question":null,"interpretation_confidence":0.9}}
                                """));

        assertThatThrownBy(() -> service.search(USER_ID, "검토 완료 점검"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_INVALID_RESPONSE);
        mockServer.verify();
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
