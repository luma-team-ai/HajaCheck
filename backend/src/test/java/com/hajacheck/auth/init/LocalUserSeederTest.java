package com.hajacheck.auth.init;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * LocalUserSeeder 멱등/스위치 로직 단위 테스트.
 * {@code @Profile("local")} 은 컨테이너 기동 없이 검증 불가한 스프링 메타데이터라
 * (기본 test 프로파일에선 애초에 빈 자체가 안 뜬다) 여기서는 run() 의 분기만 검증한다.
 * {@code @Value} 필드(seedEnabled)는 @InjectMocks 로 주입되지 않으므로 각 테스트에서 명시 설정한다.
 */
@ExtendWith(MockitoExtension.class)
class LocalUserSeederTest {

    private static final String SEED_EMAIL = "dev@hajacheck.local";
    private static final String SEED_PASSWORD = "dev1234";
    private static final String SEED_NAME = "로컬개발관리자";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private LocalUserSeeder localUserSeeder;

    @BeforeEach
    void setUp() {
        // @Value 는 Mockito 가 주입하지 않으므로 기본 활성 상태를 명시(개별 테스트에서 필요 시 override).
        ReflectionTestUtils.setField(localUserSeeder, "seedEnabled", true);
    }

    @Test
    void run_시드유저없으면_생성한다() {
        when(userRepository.findByEmail(eq(SEED_EMAIL))).thenReturn(Optional.empty());
        when(passwordEncoder.encode(eq(SEED_PASSWORD))).thenReturn("encoded-pw");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        localUserSeeder.run(null);

        verify(passwordEncoder, times(1)).encode(eq(SEED_PASSWORD));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo(SEED_EMAIL);
        assertThat(saved.getName()).isEqualTo(SEED_NAME);
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getPasswordHash()).isEqualTo("encoded-pw");
    }

    @Test
    void run_시드유저이미있으면_생성스킵하고_인코딩도안한다() {
        User existing = User.builder()
                .email(SEED_EMAIL)
                .name(SEED_NAME)
                .role(Role.ADMIN)
                .passwordHash("already-encoded")
                .status(UserStatus.ACTIVE)
                .build();
        when(userRepository.findByEmail(eq(SEED_EMAIL))).thenReturn(Optional.of(existing));

        localUserSeeder.run(null);

        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void run_시드비활성화면_DB조회도_인코딩도_안한다() {
        ReflectionTestUtils.setField(localUserSeeder, "seedEnabled", false);

        localUserSeeder.run(null);

        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }
}
