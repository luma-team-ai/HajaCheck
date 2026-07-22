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

// 관리자 API — 백엔드 GET/PATCH /api/admin/users(#405) 실 연동.
// UI 페이지 상태(AdminUsersPage, TableFooterPagination)는 1-base 관례를 쓰지만 백엔드 Pageable은
// 0-base(Spring 관례)라 여기서 한 번만 변환한다 — 화면 코드는 계속 1-base로 다룬다.
export const adminApi = {
  getUsers: (params: AdminUserListParams) =>
    api.get<AdminUserListResponse>('/admin/users', {
      params: { ...params, page: params.page - 1 },
    }),
  createUser: (payload: CreateUserPayload) => api.post<AdminUser>('/admin/users', payload),
  changeRole: (id: number, role: AdminUserRole) =>
    api.patch<RoleUpdateResult>(`/admin/users/${id}/role`, { role }),
  changeStatus: (id: number, status: AdminUserStatus) =>
    api.patch<StatusUpdateResult>(`/admin/users/${id}/status`, { status }),
};

const EXPORT_PAGE_SIZE = 100;
// 무한 루프 방지용 안전 상한 — 100 * 50 = 최대 5,000명까지 내보낼 수 있다(그 이상은 후속 과제).
const EXPORT_MAX_PAGES = 50;

// 내보내기(PDF) — 현재 필터에 해당하는 전체 사용자를 페이지 크기 상한(백엔드 MAX_PAGE_SIZE=100)만큼
// 나눠 모아온다. 화면 페이지네이션과 무관하게 "필터에 걸리는 전체 목록"을 내보내기 위함.
export async function fetchAllAdminUsers(
  filters: Omit<AdminUserListParams, 'page' | 'size'>,
): Promise<AdminUser[]> {
  const all: AdminUser[] = [];
  for (let page = 1; page <= EXPORT_MAX_PAGES; page += 1) {
    const res = await adminApi.getUsers({ ...filters, page, size: EXPORT_PAGE_SIZE });
    all.push(...res.data.content);
    if (all.length >= res.data.totalElements || res.data.content.length === 0) {
      break;
    }
  }
  return all;
}
