package com.hajacheck.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.CustomOAuth2UserService;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입 시 FREE 플랜 자동 배정(#517)의 "시드 존재 여부"에 대한 계약을 통합 테스트로 고정한다
 * (#518 PR머신 P1 반려 대응). plans 시드가 없는 배포 창(코드 먼저, 시드 나중)에서 가입 요청이
 * 어떻게 실패하는지, 시드가 있으면 정상 동작하는지를 실 PG(Testcontainers)로 검증한다.
 *
 * <p>plans 삭제는 이 테스트 트랜잭션 안에서만 유효하고 끝나면 롤백되므로, 다른 테스트가 의존하는
 * 시드(HajaCheck_script.sql init)는 오염되지 않는다(MembershipRepositoryTest 등과 동일 패턴).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FreePlanProvisioningIntegrationTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private UserPlanRepository userPlanRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CustomOAuth2UserService customOAuth2UserService;

    private MockMultipartFile brnFile() {
        return new MockMultipartFile(
                "businessRegistrationFile", "brn.png", "image/png", "PNGDATA".getBytes());
    }

    private MvcResult signup(String email, String brn) throws Exception {
        return mockMvc.perform(multipart("/api/auth/companies")
                        .file(brnFile())
                        .param("email", email)
                        .param("password", "pass1234")
                        .param("companyName", "(주)하자체크")
                        .param("businessRegistrationNumber", brn)
                        .param("representativeName", "김민수")
                        .param("businessStartDate", "2020-01-01")
                        .param("address", "서울시 강남구 테헤란로 1")
                        .param("addressDetail", "10층")
                        .param("agreeTermsOfService", "true")
                        .param("agreePrivacyPolicy", "true")
                        .with(csrf()))
                .andReturn();
    }

    private Map<String, Object> googleAttributes(String socialId, String email) {
        return Map.of(
                "sub", socialId,
                "email", email,
                "email_verified", true,
                "name", "구글영희");
    }

    @Test
    void 회사가입_FREE시드없으면_500_PLAN_DATA_INVALID() throws Exception {
        planRepository.deleteAll();
        planRepository.flush();

        MvcResult result = signup("noseed-company@haja.com", "111-11-11111");

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentAsString()).contains("PLAN_DATA_INVALID");
    }

    @Test
    void 회사가입_FREE시드있으면_201_및_FREE회사플랜배정() throws Exception {
        // 시드는 HajaCheck_script.sql init script 로 이미 존재(기본 상태) — 별도 세팅 불필요.
        MvcResult result = signup("hasseed-company@haja.com", "222-22-22222");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        com.fasterxml.jackson.databind.JsonNode data = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).get("data");
        long companyId = data.get("companyId").asLong();

        assertThat(userPlanRepository
                .findFirstByCompanyIdAndStatusOrderByStartedAtDesc(companyId, UserPlanStatus.ACTIVE))
                .isPresent();
    }

    @Test
    void 소셜신규가입_FREE시드없으면_PLAN_DATA_INVALID로_가입차단() {
        planRepository.deleteAll();
        planRepository.flush();

        // SocialAccountWriter.registerWithFreePlan 이 User 저장 + FREE 배정을 한 트랜잭션으로 묶어
        // PlanProvisioningService 실패 시 트랜잭션 전체가 rollback-only 로 표시된다(#517 설계 의도).
        // 테스트 트랜잭션 커밋 시점(테스트 종료)에 실제로 롤백되므로 User row 도 영속화되지 않는다.
        assertThatThrownBy(() -> customOAuth2UserService.processOAuth2User(
                "google", googleAttributes("noseed-social-999", "noseed-social@haja.com")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_DATA_INVALID));
    }

    @Test
    void 소셜신규가입_FREE시드있으면_정상가입및FREE개인플랜배정() {
        LoginUser result = customOAuth2UserService.processOAuth2User(
                "google", googleAttributes("hasseed-social-999", "hasseed-social@haja.com"));

        User saved = userRepository.findBySocialProviderAndSocialId(SocialProvider.GOOGLE, "hasseed-social-999")
                .orElseThrow();
        assertThat(result.getEmail()).isEqualTo("hasseed-social@haja.com");
        assertThat(userPlanRepository
                .findFirstByUserIdAndStatusOrderByStartedAtDesc(saved.getId(), UserPlanStatus.ACTIVE))
                .isPresent();
    }
}
