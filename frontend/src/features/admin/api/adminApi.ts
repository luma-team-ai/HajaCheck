import { api } from '../../../shared/api/axios';
import type { AdminUserListParams, AdminUserListResponse } from '../types';

// 관리자 API — 백엔드 계약(contract.md)에 아직 /admin/users가 없어 경로는 선제 정의.
// 계약 확정 시 경로·파라미터명을 여기서 한 번만 맞추면 된다.
export const adminApi = {
  getUsers: (params: AdminUserListParams) =>
    api.get<AdminUserListResponse>('/admin/users', { params }),
};
