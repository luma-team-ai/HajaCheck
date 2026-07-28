import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { platformAdminUserApi } from '../api/platformAdminUserApi';
import type { CounselType } from '../types';

// 스킬 변경 모달이 열릴 때만 조회한다(userId=null이면 비활성) — 목록 응답(AdminUser)에는 스킬이
// 없어(N+1 방지로 뺀 필드) 모달을 여는 시점에 별도로 가져온다.
export function useUserSkills(userId: number | null) {
  return useQuery<{ id: number; skills: CounselType[] }, ApiError>({
    queryKey: ['platform-admin', 'users', userId, 'skills'],
    queryFn: () => platformAdminUserApi.getSkills(userId as number).then((res) => res.data),
    enabled: userId !== null,
  });
}
