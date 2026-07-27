package com.hajacheck.menu.dto;

import com.hajacheck.menu.entity.Menu;
import java.util.List;

/**
 * 사이드 메뉴 트리 응답 노드. GROUP은 path/activePathPattern이 null이고 children이 채워지며,
 * 리프(INTERNAL/EXTERNAL)는 children이 빈 리스트다 — 프론트 SideNavItem/SideNavSubItem 2단 구조와
 * 1:1로 매핑되도록 재귀 대신 얕은 트리(그룹→리프)만 실제로 채워 보낸다(#1003).
 */
public record MenuTreeItemResponse(
        String code,
        String name,
        String menuType,
        String iconKey,
        String path,
        String activePathPattern,
        boolean opensNewTab,
        // 클릭 가능 여부(menus.is_enabled) — 미구현 메뉴를 표시는 하되 비활성화할 때 쓴다. 프론트
        // SideNavBar의 isRouteImplemented 안내(아직 구현되지 않은 페이지입니다)와 동일한 목적이라
        // false인 항목은 프론트에서 클릭 시 그 안내로 이어져야 한다(#1003 팔로우업).
        boolean enabled,
        List<MenuTreeItemResponse> children) {

    public static MenuTreeItemResponse of(Menu menu, List<MenuTreeItemResponse> children) {
        return new MenuTreeItemResponse(
                menu.getCode(),
                menu.getName(),
                menu.getMenuType().name(),
                menu.getIconKey(),
                menu.getPath(),
                menu.getActivePathPattern(),
                menu.isOpensNewTab(),
                menu.isEnabled(),
                children);
    }
}
