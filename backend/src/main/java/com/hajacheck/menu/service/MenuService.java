package com.hajacheck.menu.service;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.menu.dto.MenuTreeItemResponse;
import com.hajacheck.menu.entity.Menu;
import com.hajacheck.menu.entity.MenuRoleAccess;
import com.hajacheck.menu.entity.MenuType;
import com.hajacheck.menu.repository.MenuRepository;
import com.hajacheck.menu.repository.MenuRoleAccessRepository;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * role 기준으로 노출 가능한 메뉴 트리를 조회한다(#1003). 노드 수가 적어(현재 약 30개) 재귀 쿼리 대신
 * 전체를 한 번에 읽어 메모리에서 트리를 구성한다. GROUP은 menu_role_access에 매핑 행을 두지 않으므로
 * (DB 트리거로 강제) 허용된 자식이 하나라도 있으면 자동으로 포함시킨다 — menus 테이블 코멘트 규칙과
 * 동일.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuService {

    private final MenuRepository menuRepository;
    private final MenuRoleAccessRepository menuRoleAccessRepository;

    public List<MenuTreeItemResponse> getMenuTree(Role role) {
        List<Menu> allMenus = menuRepository.findAllByOrderBySortOrderAscIdAsc();
        Set<Long> accessibleLeafIds = menuRoleAccessRepository.findByRole(role).stream()
                .map(MenuRoleAccess::getMenuId)
                .collect(Collectors.toCollection(HashSet::new));

        // groupingBy 기본 구현(HashMap)은 순서를 보장하지 않는다 — 위에서 sort_order,id 순으로
        // 이미 정렬된 스트림 순서를 그대로 보존해야 형제 노드 노출 순서가 흐트러지지 않으므로
        // LinkedHashMap을 명시한다.
        Map<Long, List<Menu>> childrenByParentId = allMenus.stream()
                .filter(menu -> menu.getParentId() != null)
                .collect(Collectors.groupingBy(Menu::getParentId, LinkedHashMap::new, Collectors.toList()));

        return allMenus.stream()
                .filter(menu -> menu.getParentId() == null)
                .map(root -> buildNode(root, childrenByParentId, accessibleLeafIds))
                .filter(Objects::nonNull)
                .toList();
    }

    private MenuTreeItemResponse buildNode(
            Menu menu, Map<Long, List<Menu>> childrenByParentId, Set<Long> accessibleLeafIds) {
        if (!menu.isVisible()) {
            return null;
        }
        if (menu.getMenuType() == MenuType.GROUP) {
            List<MenuTreeItemResponse> children = childrenByParentId
                    .getOrDefault(menu.getId(), List.of())
                    .stream()
                    .map(child -> buildNode(child, childrenByParentId, accessibleLeafIds))
                    .filter(Objects::nonNull)
                    .toList();
            // 허용된 자식이 하나도 없으면 GROUP 자체를 노출하지 않는다(빈 아코디언 방지).
            if (children.isEmpty()) {
                return null;
            }
            return MenuTreeItemResponse.of(menu, children);
        }
        if (!accessibleLeafIds.contains(menu.getId())) {
            return null;
        }
        return MenuTreeItemResponse.of(menu, List.of());
    }
}
