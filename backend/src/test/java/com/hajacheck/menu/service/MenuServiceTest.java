package com.hajacheck.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.menu.dto.MenuTreeItemResponse;
import com.hajacheck.menu.entity.Menu;
import com.hajacheck.menu.entity.MenuRoleAccess;
import com.hajacheck.menu.entity.MenuType;
import com.hajacheck.menu.repository.MenuRepository;
import com.hajacheck.menu.repository.MenuRoleAccessRepository;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * role 기준 메뉴 트리 조립 로직(#1003) 단위 테스트. 실 DB 없이 리포지토리를 모킹해
 * GROUP 자동 포함/제외, 비노출(is_visible=false) 필터링, 형제 노드 정렬 보존을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock
    private MenuRepository menuRepository;
    @Mock
    private MenuRoleAccessRepository menuRoleAccessRepository;

    @InjectMocks
    private MenuService menuService;

    private Menu group(long id, String code, int sortOrder) {
        return newMenu(id, code, MenuType.GROUP, null, sortOrder, true, null);
    }

    private Menu leaf(long id, String code, Long parentId, int sortOrder) {
        return newMenu(id, code, MenuType.INTERNAL, parentId, sortOrder, true, "/" + code.toLowerCase());
    }

    private Menu invisibleLeaf(long id, String code, Long parentId, int sortOrder) {
        return newMenu(id, code, MenuType.INTERNAL, parentId, sortOrder, false, "/" + code.toLowerCase());
    }

    // Menu는 @NoArgsConstructor(PROTECTED)+필드 직접 대입 방식(Setter 없음, #1003 조회 전용 범위)이라
    // 테스트에서 리플렉션으로 필드를 채운다 — 이 이슈 범위엔 관리자 편집 API가 없어 테스트 전용 빌더를
    // 프로덕션 엔티티에 추가하는 대신 이 방식을 택했다.
    private Menu newMenu(long id, String code, MenuType type, Long parentId, int sortOrder,
                          boolean visible, String path) {
        Menu menu = newInstance(Menu.class);
        setField(menu, "id", id);
        setField(menu, "code", code);
        setField(menu, "name", code);
        setField(menu, "menuType", type);
        setField(menu, "parentId", parentId);
        setField(menu, "path", path);
        setField(menu, "sortOrder", sortOrder);
        setField(menu, "visible", visible);
        setField(menu, "enabled", true);
        setField(menu, "opensNewTab", false);
        return menu;
    }

    private MenuRoleAccess access(long menuId, Role role) {
        MenuRoleAccess menuRoleAccess = newInstance(MenuRoleAccess.class);
        setField(menuRoleAccess, "menuId", menuId);
        setField(menuRoleAccess, "role", role);
        return menuRoleAccess;
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
    void 자식이_하나도_허용되지_않은_GROUP은_트리에서_제외된다() {
        List<Menu> allMenus = List.of(
                group(1, "DASHBOARD", 10),
                leaf(2, "DASHBOARD_OVERVIEW", 1L, 10));
        when(menuRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(allMenus);
        when(menuRoleAccessRepository.findByRole(Role.COUNSELOR)).thenReturn(List.of());

        List<MenuTreeItemResponse> tree = menuService.getMenuTree(Role.COUNSELOR);

        assertThat(tree).isEmpty();
    }

    @Test
    void 자식이_하나라도_허용되면_부모_GROUP이_자동으로_포함된다() {
        List<Menu> allMenus = List.of(
                group(1, "DASHBOARD", 10),
                leaf(2, "DASHBOARD_OVERVIEW", 1L, 10),
                leaf(3, "DASHBOARD_UPCOMING", 1L, 20));
        when(menuRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(allMenus);
        when(menuRoleAccessRepository.findByRole(Role.USER)).thenReturn(List.of(access(2, Role.USER)));

        List<MenuTreeItemResponse> tree = menuService.getMenuTree(Role.USER);

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).code()).isEqualTo("DASHBOARD");
        assertThat(tree.get(0).children()).extracting(MenuTreeItemResponse::code)
                .containsExactly("DASHBOARD_OVERVIEW");
    }

    @Test
    void 비노출_리프는_role에_허용되어도_트리에서_제외된다() {
        List<Menu> allMenus = List.of(
                group(1, "DASHBOARD", 10),
                invisibleLeaf(2, "DASHBOARD_HIDDEN", 1L, 10));
        when(menuRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(allMenus);
        when(menuRoleAccessRepository.findByRole(Role.ADMIN)).thenReturn(List.of(access(2, Role.ADMIN)));

        List<MenuTreeItemResponse> tree = menuService.getMenuTree(Role.ADMIN);

        assertThat(tree).isEmpty();
    }

    @Test
    void 하위메뉴_없는_최상위_리프는_role에_허용되면_단독으로_포함된다() {
        List<Menu> allMenus = List.of(leaf(1, "STATISTICS", null, 80));
        when(menuRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(allMenus);
        when(menuRoleAccessRepository.findByRole(Role.INSPECTOR)).thenReturn(List.of(access(1, Role.INSPECTOR)));

        List<MenuTreeItemResponse> tree = menuService.getMenuTree(Role.INSPECTOR);

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).code()).isEqualTo("STATISTICS");
        assertThat(tree.get(0).children()).isEmpty();
    }

    @Test
    void 형제_노드는_repository가_반환한_순서를_그대로_보존한다() {
        // MenuService는 자체적으로 정렬하지 않고 findAllByOrderBySortOrderAscIdAsc()가 이미 정렬해
        // 반환한 순서를 그대로 신뢰한다 — 그 신뢰가 깨지지 않는지(groupingBy 기본 HashMap으로 순서가
        // 섞이지 않는지) 검증하려고 일부러 sort_order 역순으로 모킹한다.
        List<Menu> allMenus = List.of(
                group(1, "REPORTS", 10),
                leaf(3, "REPORTS_EXPORT", 1L, 30),
                leaf(2, "REPORTS_LIST", 1L, 10));
        when(menuRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(allMenus);
        when(menuRoleAccessRepository.findByRole(Role.USER))
                .thenReturn(List.of(access(2, Role.USER), access(3, Role.USER)));

        List<MenuTreeItemResponse> tree = menuService.getMenuTree(Role.USER);

        assertThat(tree.get(0).children()).extracting(MenuTreeItemResponse::code)
                .containsExactly("REPORTS_EXPORT", "REPORTS_LIST");
    }
}
