package com.hajacheck.auth.init;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로컬 개발 전용 시드 유저 — 전역 인증 강제(anyRequest().authenticated()) 이후
 * 기능별 풀스택 개발자가 로컬에서 세션 없이 막히지 않도록 자체(email/password) 로그인 가능한
 * ADMIN 계정을 기동 시 자동 생성한다.
 *
 * <p>{@code @Profile("local")} 로 제한 — docker/prod 프로파일에서는 절대 동작하지 않는다.
 * 멱등(이미 있으면 스킵)이라 반복 기동해도 중복 생성되지 않는다.
 *
 * <p><b>⚠️ 공유 DB 오염 주의</b>: {@code @Profile("local")} 은 "코드 프로파일"만 보장하고
 * "어떤 DB에 붙었는지"는 보장하지 못한다. SSH 터널로 공유 개발 DB(localhost:5432 로 포워딩)에
 * 붙은 채 local 프로파일로 기동하면 known-password ADMIN 이 공유 DB에 생성된다. 그런 경우
 * {@code app.local-seed.enabled=false} 로 시드를 꺼라(기본값 true — 로컬 격리 DB 전제).
 *
 * <p>TODO(후속): 역할 기반 인가 도입 시 여러 role(USER/INSPECTOR/COUNSELOR) 시드 확장,
 * 회사 스코프 기능 도입 시 companyId 샘플값 추가 검토.
 */
@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class LocalUserSeeder implements ApplicationRunner {

    private static final String SEED_EMAIL = "dev@hajacheck.local";
    private static final String SEED_PASSWORD = "dev1234";
    private static final String SEED_NAME = "로컬개발관리자";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 시드 on/off 스위치(기본 켜짐 — 로컬 격리 DB 전제).
     * SSH 터널로 공유 DB에 붙을 때는 application-local.yml 에서 false 로 꺼 공유 DB 오염을 막는다.
     */
    @Value("${app.local-seed.enabled:true}")
    private boolean seedEnabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            log.info("로컬 시드 유저 비활성화(app.local-seed.enabled=false) — 생성 스킵");
            return;
        }
        if (userRepository.findByEmail(SEED_EMAIL).isPresent()) {
            log.info("로컬 시드 유저 이미 존재 — 생성 스킵 (email={})", SEED_EMAIL);
            return;
        }

        User seedUser = User.builder()
                .email(SEED_EMAIL)
                .name(SEED_NAME)
                .role(Role.ADMIN)
                .passwordHash(passwordEncoder.encode(SEED_PASSWORD))
                .status(UserStatus.ACTIVE)
                .build();
        try {
            userRepository.save(seedUser);
        } catch (DataIntegrityViolationException e) {
            // 동시 기동(devtools 재시작 등)에서 다른 인스턴스가 먼저 생성한 경합 — email unique 제약에 걸린 것뿐이라 스킵.
            log.info("로컬 시드 유저 경합 감지 — 이미 생성됨, 스킵 (email={})", SEED_EMAIL);
            return;
        }

        // 더미 자격증명(로컬 전용, 실서비스 미노출) — 로그 평문 출력 OK.
        log.info("로컬 시드 유저: {} / {}", SEED_EMAIL, SEED_PASSWORD);
    }
}
