// 관리자 > 사용자 관리 도메인 타입 — Figma node-id 177-2017 "hajaCheck Admin - 사용자 관리 워크스페이스" 기준.
// 백엔드 계약(docs/api-contract/contract.md)에 아직 /admin/users 스펙이 없어, 아래 코드값은
// 화면 요구사항 기준의 선제 정의다. 계약 확정 시 이 파일과 adminApi.handlers.ts를 함께 맞출 것.

import type { Role } from '../../shared/constants/roles';

// DB users 테이블(docs/design/db/HajaCheck_script.sql)의 PG enum 라벨과 값을 일치시킨다.
// role_type = ADMIN/INSPECTOR/USER/COUNSELOR, user_status_type = ACTIVE/SUSPENDED.
//
// role은 로그인 사용자와 같은 값이라 shared/constants/roles의 Role을 그대로 쓴다 — 복제해두면
// 백엔드에 값이 추가될 때 한쪽만 고치고 넘어가 라벨 Record 조회가 undefined가 된다(빈 배지).
export type AdminUserRole = Role;
export type AdminUserStatus = 'ACTIVE' | 'SUSPENDED';

// plan은 users 컬럼이 아니라 user_plans → plans(plan_name_type) 조인 결과다.
// 구독 행이 없는 사용자가 있을 수 있어 null을 허용한다.
export type AdminUserPlan = 'FREE' | 'STANDARD' | 'ENTERPRISE';

export interface AdminUser {
  id: number;
  /** users.name — DDL상 NOT NULL */
  name: string;
  email: string;
  /** users.profile_image_url */
  avatarUrl?: string | null;
  role: AdminUserRole;
  /** 활성 구독이 없으면 null */
  plan: AdminUserPlan | null;
  /** users.created_at — DDL상 NOT NULL */
  joinedAt: string;
  /** users.last_login_at — 미접속 계정은 null */
  lastAccessAt: string | null;
  status: AdminUserStatus;
}

export interface AdminUserStats {
  totalMembers: number;
  active: number;
  suspended: number;
  newThisWeek: number;
  /** 지난주 대비 신규 가입 증감률(%) — 양수면 상승 */
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
  /** 통계 카드 값 — 필터와 무관한 전체 기준 집계 */
  stats: AdminUserStats;
}
