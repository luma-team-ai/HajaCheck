import { api } from '../../../shared/api/axios';
import type {
  AdminUser,
  AdminUserListParams,
  AdminUserListResponse,
  AdminUserRole,
  AdminUserStatus,
} from '../types';

interface RoleUpdateResult {
  id: number;
  role: AdminUserRole;
}

interface StatusUpdateResult {
  id: number;
  status: AdminUserStatus;
}

export interface CreateUserPayload {
  email: string;
  password: string;
  name: string;
  role: AdminUserRole;
}

// 플랫폼 관리자 > 사용자 관리(#577) — features/admin/api/adminApi.ts(#405)를 그대로 옮긴 것.
// 엔드포인트만 다르다: /api/admin/users는 요청 관리자의 회사로 스코프되지만(hasRole ADMIN),
// /api/platform-admin/users는 회사 스코프 없이 전체를 조회한다(hasRole PLATFORM_ADMIN) —
// 백엔드 신규 엔드포인트는 별도 워크트리(backend/576-platform-admin-users)에서 구현 중.
// UI 페이지 상태는 1-base 관례를 쓰지만 백엔드 Pageable은 0-base(Spring 관례)라 여기서 변환한다.
export const platformAdminUserApi = {
  getUsers: (params: AdminUserListParams) =>
    api.get<AdminUserListResponse>('/platform-admin/users', {
      params: { ...params, page: params.page - 1 },
    }),
  createUser: (payload: CreateUserPayload) =>
    api.post<AdminUser>('/platform-admin/users', payload),
  changeRole: (id: number, role: AdminUserRole) =>
    api.patch<RoleUpdateResult>(`/platform-admin/users/${id}/role`, { role }),
  changeStatus: (id: number, status: AdminUserStatus) =>
    api.patch<StatusUpdateResult>(`/platform-admin/users/${id}/status`, { status }),
};

const EXPORT_PAGE_SIZE = 100;
const EXPORT_MAX_PAGES = 50;

export async function fetchAllPlatformAdminUsers(
  filters: Omit<AdminUserListParams, 'page' | 'size'>,
): Promise<AdminUser[]> {
  const all: AdminUser[] = [];
  for (let page = 1; page <= EXPORT_MAX_PAGES; page += 1) {
    const res = await platformAdminUserApi.getUsers({ ...filters, page, size: EXPORT_PAGE_SIZE });
    all.push(...res.data.content);
    if (all.length >= res.data.totalElements || res.data.content.length === 0) {
      break;
    }
  }
  return all;
}
