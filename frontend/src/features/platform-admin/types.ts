// 플랫폼 관리자 > 사용자 관리 도메인 타입 — features/admin/types.ts(#405 기업 관리자 사용자 관리)를
// 그대로 옮긴 것(#577). 기업 관리자 화면은 GET /api/admin/users(회사 스코프)를 쓰고 이 화면은
// GET /api/platform-admin/users(전체 회사 스코프, PLATFORM_ADMIN 전용)를 쓰므로 API가 다르지만
// 사용자 한 명을 표현하는 필드 형태는 동일하다.

import type { Role } from '../../shared/constants/roles';

// PLATFORM_ADMIN 계정 자신은 company_id가 없어 이 목록(회사 소속 사용자)에는 나타나지 않는다(PRD v0.47).
export type AdminUserRole = Exclude<Role, 'PLATFORM_ADMIN'>;
export type AdminUserStatus = 'ACTIVE' | 'SUSPENDED';

export type AdminUserPlan = 'FREE' | 'STANDARD' | 'ENTERPRISE';

export interface AdminUser {
  id: number;
  name: string;
  email: string;
  avatarUrl?: string | null;
  role: AdminUserRole;
  plan: AdminUserPlan | null;
  joinedAt: string;
  lastAccessAt: string | null;
  status: AdminUserStatus;
}

export interface AdminUserStats {
  totalMembers: number;
  active: number;
  suspended: number;
  newThisWeek: number;
  newThisWeekGrowthRate: number;
}

export interface AdminUserListParams {
  page: number;
  size: number;
  keyword?: string;
  role?: AdminUserRole;
  plan?: AdminUserPlan;
  status?: AdminUserStatus;
}

export interface AdminUserListResponse {
  content: AdminUser[];
  page: number;
  size: number;
  totalElements: number;
  stats: AdminUserStats;
}
