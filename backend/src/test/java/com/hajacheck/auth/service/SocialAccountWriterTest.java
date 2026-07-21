package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.membership.service.PlanProvisioningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 신규 소셜 가입 User 저장 + FREE 개인 플랜 배정(#517) 연결 검증.
 */
@ExtendWith(MockitoExtension.class)
class SocialAccountWriterTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PlanProvisioningService planProvisioningService;

    @InjectMocks
    private SocialAccountWriter socialAccountWriter;

    @Test
    void registerWithFreePlan_유저저장후_FREE개인플랜배정호출() {
        User newUser = User.createSocialUser(SocialProvider.KAKAO, "1", "kakao@haja.com", "홍길동");
        User saved = User.createSocialUser(SocialProvider.KAKAO, "1", "kakao@haja.com", "홍길동");
        when(userRepository.save(any(User.class))).thenReturn(saved);

        User result = socialAccountWriter.registerWithFreePlan(newUser);

        assertThat(result).isEqualTo(saved);
        verify(userRepository).save(newUser);
        verify(planProvisioningService).ensureFreePlanForUser(any());
    }
}
