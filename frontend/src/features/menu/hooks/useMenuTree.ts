import { useQuery } from '@tanstack/react-query';
import { menuApi } from '../api/menuApi';
import type { MenuTreeItem } from '../types';

// 로그인 사용자의 role 기준으로 노출 가능한 메뉴 트리를 조회한다(#1003). 실패/로딩 중에는 undefined를
// 반환하고, 호출부(AppShellRoute)가 items/adminItem을 SideNavBar에 넘기지 않아 SideNavBar 자체 기본값
// (DEFAULT_ITEMS/DEFAULT_ADMIN_ITEM)으로 자연스럽게 폴백한다 — 별도 목 폴백 로직을 두지 않는다.
export function useMenuTree() {
  return useQuery<MenuTreeItem[]>({
    queryKey: ['menu', 'tree'],
    queryFn: () => menuApi.getMenuTree().then((res) => res.data),
  });
}
