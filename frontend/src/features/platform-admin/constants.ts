import type { SideNavItem } from '../../shared/components/SideNavBar';
import adminIcon from '../../assets/brand/sidenav-admin.svg';

// 플랫폼 관리자 콘솔 사이드바(#535) — 7개 메뉴 라벨은 기존 SideNavBar DEFAULT_ADMIN_ITEM(기업
// 관리자 콘솔)의 subItems 라벨과 동일하게 유지하고, 경로만 /platform-admin/* 로 분리한다.
// PlatformAdminShellRoute가 SideNavBar에 items=[], adminItem=이 값을 넘기면 이 그룹 하나만
// 노출된다(SideNavBar는 isAdmin=true일 때 [...items, adminItem]을 렌더).
export const PLATFORM_ADMIN_NAV_ITEM: SideNavItem = {
  label: '플랫폼 관리자',
  href: '/platform-admin',
  icon: adminIcon,
  subItems: [
    { label: '사용자 관리', href: '/platform-admin/users' },
    { label: '플랜·쿼터 관리', href: '/platform-admin/plans-quota' },
    { label: '하자 유형·등급 관리', href: '/platform-admin/defect-types' },
    { label: '상담 관리', href: '/platform-admin/counsels' },
    { label: 'RAG 문서 관리', href: '/platform-admin/rag-documents' },
    { label: '서비스 통계', href: '/platform-admin/stats' },
    { label: '시스템 모니터링', href: '/platform-admin/monitoring' },
  ],
};
