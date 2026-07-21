import { useQuery } from '@tanstack/react-query';
import { getInspectionCycleStatusRows } from '../mocks/inspectionCycle.mock';

// 전체 시설물 점검 주기 현황 — 실 목록/집계 엔드포인트가 아직 없어(handoff §8 계약확장 요청)
// feature 로컬 목 모듈을 Promise로 감싸 TanStack Query 관례(§4)를 그대로 따른다.
// getInspectionCycleStatusRows()는 mutable in-memory store를 매번 다시 읽으므로(react-reviewer P1),
// 저장 성공 후 invalidateQueries로 리페치하면 갱신된 값이 반영된다.
// 실연동 시 이 queryFn만 facilityApi 호출로 교체하면 되도록 훅 시그니처는 그대로 둔다.
export const inspectionCycleStatusListKey = ['facility', 'inspection-cycle', 'status-list'] as const;

export function useInspectionCycleStatusRows() {
  return useQuery({
    queryKey: inspectionCycleStatusListKey,
    queryFn: () => Promise.resolve(getInspectionCycleStatusRows()),
  });
}
