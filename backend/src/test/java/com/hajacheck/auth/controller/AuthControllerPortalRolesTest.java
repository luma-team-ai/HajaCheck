package com.hajacheck.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Role;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 포털 role 화이트리스트의 구조적 불변식(#1514) — 전수성·상호배타성을 단언으로 고정한다.
 *
 * <p><b>⚠️ 적용 범위: 비밀번호 로그인 경로(AuthController)뿐이다.</b> 여기서 고정하는 것은
 * <b>role 축의 분할</b>(한 role 이 정확히 한 포털 화이트리스트에만 속한다)이지 "한 계정이 한 화면으로만
 * 로그인된다"가 아니다. 소셜 로그인은 이 게이트 밖이라, 승격된 소셜 COUNSELOR 계정은 기업 화면의 소셜
 * 버튼으로도 로그인된다(AuthController 클래스 javadoc · 후속 #1519). 그 예외를 이 테스트가 막아주지
 * 않는다는 점을 오해하면 안 된다.
 *
 * <p>AuthController 의 상수들은 주석으로 "겹치지 않는다"고 선언만 하고 검증이 없었다(리뷰 P3-2).
 * {@link Role} 에 값이 추가되면 그 role 은 <b>어느 포털로도 로그인할 수 없게</b> 되는데, fail-closed 라
 * 안전한 대신 <b>아무 신호 없이</b> 그렇게 된다 — 새 role 계정이 "비밀번호는 맞는데 403"을 맞고 나서야
 * 발견된다. 이 테스트가 그 시점을 컴파일·테스트 단계로 앞당긴다: Role 이 늘면 여기서 먼저 깨지고,
 * 추가한 사람이 "이 role 은 어느 화면으로 로그인하는가"를 강제로 결정하게 된다.
 *
 * <p>Spring 컨텍스트가 필요 없는 순수 단위 테스트다(상수만 본다).
 */
class AuthControllerPortalRolesTest {

    // NO_PORTAL_ROLES(로그인 불가로 "명시 선언"된 role)까지 포함해야 전수성 검사가 의미를 갖는다 —
    // 이게 빠지면 로그인시키면 안 되는 role 을 그리로 넣는 순간 테스트가 RED 가 되어, 결국 기존
    // 화이트리스트에 밀어 넣는 쪽으로 유도된다(= 권한 확대 압력).
    private static final List<Set<Role>> PORTALS = List.of(
            AuthController.COMPANY_PORTAL_ROLES,
            AuthController.PLATFORM_ADMIN_PORTAL_ROLES,
            AuthController.COUNSELOR_PORTAL_ROLES,
            AuthController.NO_PORTAL_ROLES);

    @Test
    void 모든_Role은_정확히_하나의_포털에_배정된다() {
        // 전수성: 어떤 Role 도 "배정 자체가 누락된" 채로 방치되지 않는다.
        // 로그인시키면 안 되는 role 은 NO_PORTAL_ROLES 에 넣으면 되므로, 이 단언은 "무조건 로그인
        // 가능하게 만들라"는 압력이 아니라 "의도를 명시하라"는 압력이다.
        Set<Role> union = EnumSet.noneOf(Role.class);
        PORTALS.forEach(union::addAll);

        assertThat(union)
                .as("Role 에 값을 추가했다면 AuthController 의 포털 화이트리스트 중 하나에 배정해야 한다 "
                        + "(로그인시키면 안 되는 role 이면 NO_PORTAL_ROLES 에 넣어 의도를 명시할 것)")
                .isEqualTo(EnumSet.allOf(Role.class));
    }

    @Test
    void 포털_화이트리스트는_서로_겹치지_않는다() {
        // 상호배타성: 한 role 은 정확히 한 포털 화이트리스트에만 속한다.
        // ⚠️ "한 계정은 한 포털에만 로그인 가능"을 뜻하지 않는다 — 소셜 경로는 이 게이트 밖이라
        //    승격된 소셜 COUNSELOR 는 기업 화면으로도 로그인된다(AuthController javadoc · #1519).
        for (int i = 0; i < PORTALS.size(); i++) {
            for (int j = i + 1; j < PORTALS.size(); j++) {
                Set<Role> overlap = EnumSet.noneOf(Role.class);
                overlap.addAll(PORTALS.get(i));
                overlap.retainAll(PORTALS.get(j));

                assertThat(overlap)
                        .as("포털 화이트리스트 %d 와 %d 가 겹치면 한 계정이 두 화면으로 로그인된다", i, j)
                        .isEmpty();
            }
        }
    }

    @Test
    void 화이트리스트의_합은_중복없이_Role_개수와_같다() {
        // 위 두 단언의 교차 검증 — 네 집합의 크기 합이 전체 Role 수와 같아야
        // "빠짐없이(전수), 겹치지 않게(상호배타)" 가 동시에 성립한다.
        List<Role> flattened = new ArrayList<>();
        PORTALS.forEach(flattened::addAll);

        assertThat(flattened).hasSize(Role.values().length);
    }
}
