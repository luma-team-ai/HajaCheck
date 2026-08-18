import type { DefectStatus, InspectionDefect } from '../types';

export interface DefectGroupSummary {
  size: number;
  status: DefectStatus;
}

// 사진(mediaId) 단위 조치 상태 요약(#1644) — 하자 상세 모달 헤더 상태 칩과 조치 등록 폼의 사전
// 그룹 안내에서 공용으로 쓴다. 백엔드 DefectService.resolveActionGroup/aggregateGroupStatus
// (DefectService.java:192-217)와 동일한 판정 규칙을 그대로 재현한다:
//  - mediaId가 없으면(수동 추가 하자) 그룹 크기 1, 자기 상태 그대로.
//  - 있으면 같은 mediaId를 가진, DETECTED(검수 전)가 아닌 하자 전체가 그룹이다.
//  - 그룹 전체가 RESOLVED면 RESOLVED, 하나라도 IN_PROGRESS 이상이면 IN_PROGRESS, 그 외(전부
//    CONFIRMED)는 CONFIRMED.
// groupSize/groupStatus(DefectResponse.java:38-43)는 조치 등록 PATCH 응답에서만 계산돼 목록/단순
// 상세 조회는 항상 null이다 — 등록 "전"에도 그룹 상태를 보여주려면 백엔드 값을 기다릴 수 없어,
// 이미 화면이 들고 있는 defects(같은 사진의 group-eligible 하자 배열)에서 프론트가 직접 집계한다.
export function resolveDefectGroupSummary(
  defects: InspectionDefect[],
  selectedDefect: InspectionDefect,
): DefectGroupSummary {
  if (selectedDefect.mediaId == null) {
    return { size: 1, status: selectedDefect.status };
  }

  const members = defects.filter(
    (defect) => defect.status !== 'DETECTED' && defect.mediaId === selectedDefect.mediaId,
  );
  if (!members.some((defect) => defect.id === selectedDefect.id)) {
    members.push(selectedDefect);
  }
  if (members.length <= 1) {
    return { size: members.length, status: selectedDefect.status };
  }

  const allResolved = members.every((defect) => defect.status === 'RESOLVED');
  if (allResolved) {
    return { size: members.length, status: 'RESOLVED' };
  }
  const anyInProgressOrAbove = members.some(
    (defect) => defect.status === 'IN_PROGRESS' || defect.status === 'RESOLVED',
  );
  return { size: members.length, status: anyInProgressOrAbove ? 'IN_PROGRESS' : 'CONFIRMED' };
}
