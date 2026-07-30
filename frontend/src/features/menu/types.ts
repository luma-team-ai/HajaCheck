// 백엔드 MenuTreeItemResponse(#1003, backend/src/main/java/com/hajacheck/menu/dto)와 1:1.
export type MenuNodeType = 'GROUP' | 'INTERNAL' | 'EXTERNAL';

export interface MenuTreeItem {
  code: string;
  name: string;
  menuType: MenuNodeType;
  iconKey: string | null;
  path: string | null;
  activePathPattern: string | null;
  opensNewTab: boolean;
  // 클릭 가능 여부(menus.is_enabled) — false는 "표시는 하되 비활성화"를 의미한다. SideNavBar가
  // 아직 이 값으로 비활성 스타일/클릭 차단을 렌더링하지 않으므로(#1003 팔로우업), 현재는 타입에만
  // 반영해두고 toSideNavItems는 이 값을 소비하지 않는다.
  enabled: boolean;
  children: MenuTreeItem[];
}
