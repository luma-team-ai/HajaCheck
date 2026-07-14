package com.hajacheck.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

@ExtendWith(MockitoExtension.class)
class CustomOAuth2UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CustomOAuth2UserService customOAuth2UserService;

    private Map<String, Object> kakaoAttributes() {
        return Map.of(
                "id", 4823001L,
                "kakao_account", Map.of(
                        "email", "kakao@haja.com",
                        "is_email_verified", true,
                        "profile", Map.of("nickname", "카카오길동")));
    }

    private Map<String, Object> googleAttributes() {
        return Map.of(
                "sub", "google-sub-999",
                "email", "google@haja.com",
                "email_verified", true,
                "name", "구글영희");
    }

    @Test
    void processOAuth2User_카카오신규_가입후principal반환() {
        when(userRepository.findBySocialProviderAndSocialId(eq(SocialProvider.KAKAO), eq("4823001")))
                .thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        LoginUser result = customOAuth2UserService.processOAuth2User("kakao", kakaoAttributes());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getSocialProvider()).isEqualTo(SocialProvider.KAKAO);
        assertThat(saved.getSocialId()).isEqualTo("4823001");
        assertThat(saved.getEmail()).isEqualTo("kakao@haja.com");
        assertThat(saved.getName()).isEqualTo("카카오길동");
        assertThat(saved.getPasswordHash()).isNull();
        assertThat(result.getEmail()).isEqualTo("kakao@haja.com");
        assertThat(result.getAttributes()).containsKey("kakao_account");
    }

    @Test
    void processOAuth2User_구글신규_가입후principal반환() {
        when(userRepository.findBySocialProviderAndSocialId(eq(SocialProvider.GOOGLE), eq("google-sub-999")))
                .thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        LoginUser result = customOAuth2UserService.processOAuth2User("google", googleAttributes());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getSocialProvider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(captor.getValue().getSocialId()).isEqualTo("google-sub-999");
        assertThat(captor.getValue().getName()).isEqualTo("구글영희");
        assertThat(result.getEmail()).isEqualTo("google@haja.com");
    }

    @Test
    void processOAuth2User_기존사용자_저장없이principal반환() {
        User existing = User.createSocialUser(
                SocialProvider.KAKAO, "4823001", "kakao@haja.com", "카카오길동");
        when(userRepository.findBySocialProviderAndSocialId(SocialProvider.KAKAO, "4823001"))
                .thenReturn(Optional.of(existing));

        LoginUser result = customOAuth2UserService.processOAuth2User("kakao", kakaoAttributes());

        verify(userRepository, never()).save(any(User.class));
        assertThat(result.getEmail()).isEqualTo("kakao@haja.com");
        assertThat(existing.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void processOAuth2User_카카오이메일미검증_placeholder로가입성공() {
        // 미검증 이메일은 신뢰하지 않고 placeholder 로 대체 → 실제 이메일 미저장(#199)
        when(userRepository.findBySocialProviderAndSocialId(eq(SocialProvider.KAKAO), eq("4823001")))
                .thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> account = new HashMap<>();
        account.put("email", "kakao@haja.com");
        account.put("is_email_verified", false); // 미검증
        account.put("profile", Map.of("nickname", "카카오길동"));
        Map<String, Object> attributes = Map.of("id", 4823001L, "kakao_account", account);

        LoginUser result = customOAuth2UserService.processOAuth2User("kakao", attributes);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("kakao_4823001@social.local");
        assertThat(saved.getEmail()).isNotEqualTo("kakao@haja.com"); // 미검증 실제값은 저장 안 함
        assertThat(saved.getSocialProvider()).isEqualTo(SocialProvider.KAKAO);
        assertThat(saved.getSocialId()).isEqualTo("4823001");
        assertThat(result.getEmail()).isEqualTo("kakao_4823001@social.local");
    }

    @Test
    void processOAuth2User_카카오이메일없음_placeholder로가입성공() {
        when(userRepository.findBySocialProviderAndSocialId(eq(SocialProvider.KAKAO), eq("4823001")))
                .thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> account = new HashMap<>();
        account.put("is_email_verified", true);
        account.put("profile", Map.of("nickname", "카카오길동"));
        Map<String, Object> attributes = Map.of("id", 4823001L, "kakao_account", account);

        LoginUser result = customOAuth2UserService.processOAuth2User("kakao", attributes);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("kakao_4823001@social.local");
        assertThat(saved.getSocialProvider()).isEqualTo(SocialProvider.KAKAO);
        assertThat(saved.getSocialId()).isEqualTo("4823001");
        assertThat(saved.getName()).isEqualTo("카카오길동");
        assertThat(result.getEmail()).isEqualTo("kakao_4823001@social.local");
    }

    @Test
    void processOAuth2User_카카오placeholder재로그인_기존사용자재사용() {
        // 이메일 없이 placeholder 로 가입한 계정의 재로그인 → socialId 로 식별, 중복 가입 없음(#199)
        User existing = User.createSocialUser(
                SocialProvider.KAKAO, "4823001", "kakao_4823001@social.local", "카카오길동");
        when(userRepository.findBySocialProviderAndSocialId(SocialProvider.KAKAO, "4823001"))
                .thenReturn(Optional.of(existing));

        Map<String, Object> account = new HashMap<>();
        account.put("is_email_verified", true);
        account.put("profile", Map.of("nickname", "카카오길동"));
        Map<String, Object> attributes = Map.of("id", 4823001L, "kakao_account", account);

        LoginUser result = customOAuth2UserService.processOAuth2User("kakao", attributes);

        verify(userRepository, never()).save(any(User.class));
        assertThat(result.getEmail()).isEqualTo("kakao_4823001@social.local");
    }

    @Test
    void processOAuth2User_저장충돌_재조회로복구() {
        User winner = User.createSocialUser(
                SocialProvider.KAKAO, "4823001", "kakao@haja.com", "카카오길동");
        // 첫 조회는 없음 → save 시 unique 위반(경합) → 재조회에서 먼저 저장된 레코드 반환
        when(userRepository.findBySocialProviderAndSocialId(SocialProvider.KAKAO, "4823001"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("uk_users_social"));

        LoginUser result = customOAuth2UserService.processOAuth2User("kakao", kakaoAttributes());

        assertThat(result.getEmail()).isEqualTo("kakao@haja.com");
        verify(userRepository, times(2))
                .findBySocialProviderAndSocialId(SocialProvider.KAKAO, "4823001");
    }

    @Test
    void processOAuth2User_정지계정소셜로그인_차단() {
        User suspended = User.builder()
                .email("kakao@haja.com")
                .name("정지회원")
                .socialProvider(SocialProvider.KAKAO)
                .socialId("4823001")
                .status(UserStatus.SUSPENDED)
                .build();
        when(userRepository.findBySocialProviderAndSocialId(SocialProvider.KAKAO, "4823001"))
                .thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> customOAuth2UserService.processOAuth2User("kakao", kakaoAttributes()))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("정지");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void processOAuth2User_email충돌_명확한실패() {
        // (provider, socialId) 는 신규지만 email unique 충돌로 save 실패 → 재조회도 없음 → 명확 실패
        when(userRepository.findBySocialProviderAndSocialId(SocialProvider.KAKAO, "4823001"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("users_email_key"));

        assertThatThrownBy(() -> customOAuth2UserService.processOAuth2User("kakao", kakaoAttributes()))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(ex -> assertThat(((OAuth2AuthenticationException) ex).getError().getErrorCode())
                        .isEqualTo("email_already_registered"));
    }

    @Test
    void processOAuth2User_카카오socialId누락_예외로가입차단() {
        // id 누락 → String.valueOf(null)="null" 문자열 → 신원 앵커 붕괴 방지(교차 dedup 차단, #199)
        Map<String, Object> attributes = Map.of(
                "kakao_account", Map.of("is_email_verified", true,
                        "profile", Map.of("nickname", "카카오길동")));

        assertThatThrownBy(() -> customOAuth2UserService.processOAuth2User("kakao", attributes))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(ex -> assertThat(((OAuth2AuthenticationException) ex).getError().getErrorCode())
                        .isEqualTo("invalid_request"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void processOAuth2User_구글sub누락_예외로가입차단() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", null);
        attributes.put("email", "google@haja.com");
        attributes.put("email_verified", true);
        attributes.put("name", "구글영희");

        assertThatThrownBy(() -> customOAuth2UserService.processOAuth2User("google", attributes))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(ex -> assertThat(((OAuth2AuthenticationException) ex).getError().getErrorCode())
                        .isEqualTo("invalid_request"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void processOAuth2User_구글sub공백_예외로가입차단() {
        Map<String, Object> attributes = Map.of(
                "sub", "   ",
                "email", "google@haja.com",
                "email_verified", true,
                "name", "구글영희");

        assertThatThrownBy(() -> customOAuth2UserService.processOAuth2User("google", attributes))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(ex -> assertThat(((OAuth2AuthenticationException) ex).getError().getErrorCode())
                        .isEqualTo("invalid_request"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void processOAuth2User_구글sub리터럴null문자열_예외로가입차단() {
        Map<String, Object> attributes = Map.of(
                "sub", "null",
                "email", "google@haja.com",
                "email_verified", true,
                "name", "구글영희");

        assertThatThrownBy(() -> customOAuth2UserService.processOAuth2User("google", attributes))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(ex -> assertThat(((OAuth2AuthenticationException) ex).getError().getErrorCode())
                        .isEqualTo("invalid_request"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void processOAuth2User_구글이메일미검증_placeholder로가입성공() {
        // 정책 공통 적용: 미검증 이메일은 신뢰하지 않고 placeholder 로 대체(#199)
        when(userRepository.findBySocialProviderAndSocialId(eq(SocialProvider.GOOGLE), eq("google-sub-999")))
                .thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> attributes = Map.of(
                "sub", "google-sub-999",
                "email", "google@haja.com",
                "email_verified", false,
                "name", "구글영희");

        LoginUser result = customOAuth2UserService.processOAuth2User("google", attributes);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("google_google-sub-999@social.local");
        assertThat(saved.getEmail()).isNotEqualTo("google@haja.com"); // 미검증 실제값은 저장 안 함
        assertThat(result.getEmail()).isEqualTo("google_google-sub-999@social.local");
    }
}
