package com.hajacheck.demo.init;

import com.hajacheck.auth.config.DemoProperties;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.demo.service.DemoSeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 데모 회사·계정·콘텐츠 시드 러너(#1626) — {@code LocalUserSeeder} 패턴(ApplicationRunner + 옵트인
 * 스위치 + 멱등). 차이점: 프로파일 제한이 없다 — 데모는 운영(라이브 데모) 환경에서 쓰라고 만든
 * 기능이라 {@code @Profile("local")} 로 막을 수 없고, 대신 <b>기본 꺼짐 스위치 + 크레덴셜 env 필수</b>
 * 두 겹이 오발동을 막는다.
 *
 * <p>멱등: 데모 loginId 계정이 이미 있으면 스킵한다. 경합(동시 기동)은 이메일/사업자번호 unique
 * 제약이 최종 방어하고, 여기서는 삼켜서 기동을 막지 않는다.
 *
 * <p>⚠️ 비밀번호는 환경변수 {@code DEMO_ADMIN_PASSWORD} 주입이며 로그에 남기지 않는다
 * (LocalUserSeeder 의 "로컬 전용 더미값 평문 로그"와 달리 이 값은 운영 시크릿이다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    private final DemoProperties demoProperties;
    private final UserRepository userRepository;
    private final DemoSeedService demoSeedService;

    /** 시드 on/off 스위치(기본 꺼짐 — 옵트인). LocalUserSeeder(app.local-seed.enabled)와 동일 패턴. */
    @Value("${app.demo-seed.enabled:false}")
    private boolean seedEnabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            log.debug("데모 시드 비활성화(app.demo-seed.enabled=false) — 생성 스킵");
            return;
        }
        if (demoProperties.getAdminPassword() == null || demoProperties.getAdminPassword().isBlank()) {
            // 스위치는 켰는데 크레덴셜이 없다 — 빈 비밀번호 계정을 만드느니 시드하지 않는 게 안전하다.
            log.warn("데모 시드 스킵 — DEMO_ADMIN_PASSWORD 미설정(app.demo-seed.enabled=true 인데 크레덴셜 없음)");
            return;
        }
        if (userRepository.findByEmail(demoProperties.getLoginId()).isPresent()) {
            log.info("데모 계정 이미 존재 — 시드 스킵 (loginId={})", demoProperties.getLoginId());
            return;
        }
        try {
            demoSeedService.seedAll();
        } catch (DataIntegrityViolationException e) {
            // 동시 기동 경합 — 다른 인스턴스가 먼저 시드했다(email/brn unique). 기동을 막지 않는다.
            log.info("데모 시드 경합 감지 — 이미 생성됨, 스킵 (loginId={})", demoProperties.getLoginId());
        }
    }
}
