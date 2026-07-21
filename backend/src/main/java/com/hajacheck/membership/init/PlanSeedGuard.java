package com.hajacheck.membership.init;

import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.repository.PlanRepository;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 {@code plans} 시드(FREE/STANDARD/ENTERPRISE) 존재를 검증한다 — PR #518 P1(ai:p1-blocked) 대응.
 *
 * <p>배경: 운영(arm1)은 main 자동배포 + DB 시드는 운영자가 수동 적용(docs/design/db/migrations/README.md)이라
 * "코드 먼저, 시드 나중" 배포 창이 실제로 존재한다. 그 창에서 트래픽을 받으면 가입 요청마다
 * {@link com.hajacheck.membership.service.PlanProvisioningService} 가 매번 PLAN_DATA_INVALID(500)로
 * 실패한다(#517). 조기 실패(fail-fast)로 바꿔 "시드 없는 채로 애플리케이션이 떠서 트래픽을 받는" 상태 자체를
 * 차단한다 — 앱이 아예 뜨지 않으면 배포 파이프라인/헬스체크가 먼저 잡아낸다.
 *
 * <p>토글: {@code hajacheck.membership.seed-guard.enabled}(기본 true — 안전측 fail-closed).
 * 아래 두 경우만 명시적으로 false 로 끈다(시드가 없는 상태 자체가 정상 시나리오이기 때문):
 * <ul>
 *   <li>테스트 프로파일({@code application-test.yml}) — H2(ddl-auto=create-drop)로 붙는
 *       {@code @SpringBootTest} 는 엔티티 기반 빈 스키마만 생성하고 SQL 시드는 적용하지 않는다.</li>
 *   <li>로컬 최초 부트스트랩({@code application-local.yml.example}) — ddl-auto=update 로 빈 로컬 PG에서
 *       시작하는 첫 기동은 시드가 없는 게 정상이다(docs/conventions/로컬_개발_가이드.md §4).
 *       {@link com.hajacheck.auth.init.LocalUserSeeder} 의 옵트인 스위치와 같은 이유:
 *       "안전한 기본값 + 예외적 상황만 명시적으로 끔".</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanSeedGuard implements ApplicationRunner {

    private final PlanRepository planRepository;

    @Value("${hajacheck.membership.seed-guard.enabled:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("plans 시드 가드 비활성화(hajacheck.membership.seed-guard.enabled=false) — 검증 스킵");
            return;
        }

        List<PlanName> missing = Arrays.stream(PlanName.values())
                .filter(name -> planRepository.findByName(name).isEmpty())
                .toList();

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "plans 시드 누락 — " + missing + " 가 DB에 존재하지 않습니다. "
                    + "docs/design/db/migrations/20260721_01_plans_seed_free_assign.sql(기존 운영 DB) 또는 "
                    + "docs/design/db/HajaCheck_script.sql(신규 설치)로 plans 시드를 먼저 적용한 뒤 재기동하세요. "
                    + "시드 없이 기동하면 가입 시 FREE 플랜 자동 배정(#517)이 전부 PLAN_DATA_INVALID(500)로 실패합니다.");
        }

        log.info("plans 시드 검증 완료 — FREE/STANDARD/ENTERPRISE 모두 존재합니다.");
    }
}
