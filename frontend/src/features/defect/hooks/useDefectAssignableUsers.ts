import { useQuery } from '@tanstack/react-query';
import { defectAssigneeApi } from '../api/defectAssigneeApi';

// 하자 상세 모달 "담당자" select 옵션 — facility feature의 useFacilityAssignableUsers.ts와 동일
// 패턴(#690 실 API 없음, 이번 범위는 MSW 목 전용). feature 간 직접 import 금지로 자체 복제.
export function useDefectAssignableUsers() {
  return useQuery({
    queryKey: ['defect', 'assignable-users'] as const,
    queryFn: () => defectAssigneeApi.listAssignableUsers().then((res) => res.data),
  });
}
