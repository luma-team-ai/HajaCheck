import type { FacilityInspectionOverview } from '../types';

// 시설물 상세 "점검 이력" 탭 — Figma "hajaCheck Facility Detail - Fixed Images"(node-id 1-1401) 캡처 그대로.
// 회차별 하자 집계·누적 통계 엔드포인트가 아직 없어(점검/하자 모듈에 목록 조회 API 미구현) feature 로컬 목.
// 실연동 시 GET /api/facilities/{id}/inspections 류 엔드포인트로 교체 예정(로그인 사용자 소유 시설물 한정).
export function getFacilityInspectionOverview(facilityId: number): FacilityInspectionOverview {
  // 데모는 강남 오피스타워 A동(mockFacilities id=1)만 상세 목데이터를 갖는다.
  if (facilityId !== 1) {
    return {
      overallGrade: null,
      totalRounds: 0,
      cumulativeDefectCount: 0,
      unresolvedDefectCount: 0,
      history: [],
    };
  }

  return {
    overallGrade: 'D',
    totalRounds: 8,
    cumulativeDefectCount: 43,
    unresolvedDefectCount: 12,
    history: [
      {
        id: 8,
        roundNo: 8,
        inspectionDate: '2026-06-21',
        inspectorName: '이엔지',
        status: '검수완료',
        imageCount: 214,
        defectGradeBreakdown: [
          { grade: 'E', count: 1 },
          { grade: 'D', count: 3 },
          { grade: 'C', count: 8 },
        ],
        changeNote: '이전 회차 대비 신규 하자 +4건 · 진행성 균열 2건',
        additionalImageCount: 212,
      },
      {
        id: 7,
        roundNo: 7,
        inspectionDate: '2025-12-10',
        inspectorName: '내부점검',
        status: '완료',
        imageCount: 180,
        defectGradeBreakdown: [
          { grade: 'D', count: 2 },
          { grade: 'C', count: 6 },
        ],
      },
      {
        id: 6,
        roundNo: 6,
        inspectionDate: '2025-06-15',
        inspectorName: '이엔지',
        status: '완료',
        imageCount: 155,
        defectGradeBreakdown: [{ grade: 'C', count: 5 }],
      },
    ],
  };
}
