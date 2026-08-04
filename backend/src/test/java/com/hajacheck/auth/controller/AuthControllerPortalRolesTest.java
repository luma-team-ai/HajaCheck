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
 * <p>AuthController 의 세 상수는 주석으로 "겹치지 않는다"고 선언만 하고 검증이 없었다(리뷰 P3-2).
 * {@link Role} 에 값이 추가되면 그 role 은 <b>어느 포털로도 로그인할 수 없게</b> 되는데, fail-closed 라
 * 안전한 대신 <b>아무 신호 없이</b> 그렇게 된다 — 새 role 계정이 "비밀번호는 맞는데 403"을 맞고 나서야
 * 발견된다. 이 테스트가 그 시점을 컴파일·테스트 단계로 앞당긴다: Role 이 늘면 여기서 먼저 깨지고,
 * 추가한 사람이 "이 role 은 어느 화면으로 로그인하는가"를 강제로 결정하게 된다.
 *
 * <p>Spring 컨텍스트가 필요 없는 순수 단위 테스트다(상수만 본다).
 */
class AuthControllerPortalRolesTest {

    private static final List<Set<Role>> PORTALS = List.of(
            AuthController.COMPANY_PORTAL_ROLES,
            AuthController.PLATFORM_ADMIN_PORTAL_ROLES,
            AuthController.COUNSELOR_PORTAL_ROLES);

    @Test
    void 모든_Role은_정확히_하나의_포털에_배정된다() {
        // 전수성: 어떤 Role 도 "어느 포털로도 로그인 불가" 상태로 방치되지 않는다.
        Set<Role> union = EnumSet.noneOf(Role.class);
        PORTALS.forEach(union::addAll);

        assertThat(union)
                .as("Role 에 값을 추가했다면 AuthController 의 포털 화이트리스트에도 배정해야 한다 "
                        + "(누락 시 그 role 은 아무 화면으로도 로그인할 수 없다)")
                .isEqualTo(EnumSet.allOf(Role.class));
    }

    @Test
    void 포털_화이트리스트는_서로_겹치지_않는다() {
        // 상호배타성: 한 계정은 정확히 한 포털에만 로그인할 수 있다(화면 분리의 전제).
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
        // 위 두 단언의 교차 검증 — 크기 합이 전체 Role 수와 같아야 "빠짐없이, 겹치지 않게" 가 성립한다.
        List<Role> flattened = new ArrayList<>();
        PORTALS.forEach(flattened::addAll);

        assertThat(flattened).hasSize(Role.values().length);
    }
}
