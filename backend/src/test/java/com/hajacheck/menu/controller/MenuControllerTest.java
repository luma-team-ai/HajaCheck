package com.hajacheck.menu.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.menu.entity.Menu;
import com.hajacheck.menu.entity.MenuRoleAccess;
import com.hajacheck.menu.entity.MenuType;
import com.hajacheck.menu.repository.MenuRepository;
import com.hajacheck.menu.repository.MenuRoleAccessRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메뉴 트리 조회 API(#1003) MVC 통합 테스트. GROUP 자동 포함/role별 접근 제어가 실 PG 트리거
 * (trg_menu_role_access_reject_group)·named enum(role_type/menu_node_type)과 함께 동작하는지
 * 검증해야 해서 AdminUserControllerTest와 동일하게 @SpringBootTest+MockMvc+PostgresTestSupport를 쓴다.
 * 테스트 프로파일은 Flyway를 끄므로(V19 시드 미적용) 각 테스트가 필요한 menus/menu_role_access
 * 행을 직접 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MenuControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MenuRepository menuRepository;
    @Autowired
    private MenuRoleAccessRepository menuRoleAccessRepository;

    private long userSeq;

    private User saveUser(Role role) {
        userSeq++;
        return userRepository.save(User.builder()
                .email("menu-user-" + userSeq + "@haja.com")
                .name("사용자" + userSeq)
                .role(role)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    // Menu/MenuRoleAccess는 @NoArgsConstructor(PROTECTED)+Setter 없음(#1003 조회 전용 범위라 관리자
    // 편집 API가 아직 없다) — 테스트에서 리플렉션으로 필드를 채운 뒤 저장한다.
    private Menu saveMenu(String code, MenuType type, Long parentId, int sortOrder, boolean visible) {
        Menu menu = newInstance(Menu.class);
        setField(menu, "code", code);
        setField(menu, "name", code);
        setField(menu, "menuType", type);
        setField(menu, "parentId", parentId);
        setField(menu, "path", type == MenuType.GROUP ? null : "/" + code.toLowerCase());
        setField(menu, "iconKey", type == MenuType.GROUP ? "group-icon" : "leaf-icon");
        setField(menu, "sortOrder", sortOrder);
        setField(menu, "visible", visible);
        setField(menu, "enabled", true);
        setField(menu, "opensNewTab", false);
        return menuRepository.save(menu);
    }

    private void grantAccess(Long menuId, Role role) {
        MenuRoleAccess access = newInstance(MenuRoleAccess.class);
        setField(access, "menuId", menuId);
        setField(access, "role", role);
        menuRoleAccessRepository.save(access);
    }

    @SuppressWarnings("unchecked")
    private <T> T newInstance(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (T) constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 메뉴트리조회_role에_허용된_리프와_부모GROUP만_반환한다() throws Exception {
        Menu group = saveMenu("DASHBOARD", MenuType.GROUP, null, 10, true);
        Menu allowedLeaf = saveMenu("DASHBOARD_OVERVIEW", MenuType.INTERNAL, group.getId(), 10, true);
        saveMenu("DASHBOARD_UPCOMING", MenuType.INTERNAL, group.getId(), 20, true); // 미허용
        grantAccess(allowedLeaf.getId(), Role.USER);
        User user = saveUser(Role.USER);

        mockMvc.perform(get("/api/menus").with(authentication(authOf(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("DASHBOARD"))
                .andExpect(jsonPath("$.data[0].children.length()").value(1))
                .andExpect(jsonPath("$.data[0].children[0].code").value("DASHBOARD_OVERVIEW"));
    }

    @Test
    void 메뉴트리조회_ADMIN전용메뉴는_다른role에는_노출되지_않는다() throws Exception {
        Menu adminGroup = saveMenu("ADMIN", MenuType.GROUP, null, 100, true);
        Menu adminLeaf = saveMenu("ADMIN_USERS", MenuType.INTERNAL, adminGroup.getId(), 10, true);
        grantAccess(adminLeaf.getId(), Role.ADMIN);
        User inspector = saveUser(Role.INSPECTOR);

        mockMvc.perform(get("/api/menus").with(authentication(authOf(inspector))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void 메뉴트리조회_비노출_메뉴는_허용된_role이어도_제외된다() throws Exception {
        Menu leaf = saveMenu("SETTINGS", MenuType.INTERNAL, null, 90, false);
        grantAccess(leaf.getId(), Role.USER);
        User user = saveUser(Role.USER);

        mockMvc.perform(get("/api/menus").with(authentication(authOf(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void 메뉴트리조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/menus"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
