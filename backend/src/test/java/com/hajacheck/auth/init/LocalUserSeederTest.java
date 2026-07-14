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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * LocalUserSeeder 멱등 로직 단위 테스트.
 * {@code @Profile("local")} 은 컨테이너 기동 없이 검증 불가한 스프링 메타데이터라
 * (기본 test 프로파일에선 애초에 빈 자체가 안 뜬다) 여기서는 run() 의 멱등 분기만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class LocalUserSeederTest {

    private static final String SEED_EMAIL = "dev@hajacheck.local";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private LocalUserSeeder localUserSeeder;

    @Test
    void run_시드유저없으면_생성한다() {
        when(userRepository.findByEmail(eq(SEED_EMAIL))).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("encoded-pw");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        localUserSeeder.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo(SEED_EMAIL);
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getPasswordHash()).isEqualTo("encoded-pw");
    }

    @Test
    void run_시드유저이미있으면_생성스킵한다() {
        User existing = User.builder()
                .email(SEED_EMAIL)
                .name("로컬개발관리자")
                .role(Role.ADMIN)
                .passwordHash("already-encoded")
                .status(UserStatus.ACTIVE)
                .build();
        when(userRepository.findByEmail(eq(SEED_EMAIL))).thenReturn(Optional.of(existing));

        localUserSeeder.run(null);

        verify(userRepository, never()).save(any(User.class));
    }
}
