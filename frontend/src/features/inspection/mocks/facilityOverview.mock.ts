import type { FacilityOverview } from '../types';

// 점검(회차) 생성 화면 상단 개요 패널 — Figma "hajaCheck Facility Detail - Fixed Images"(node-id 1-1401)
// dev mode 마크업 그대로. facility feature의 동일 목(facilityInspectionOverview.mock.ts)과 같은 데모
// 시설물(강남 오피스타워 A동, id=1) 기준 — feature 간 직접 import 금지라 값만 동일하게 로컬 복제.
// 회차별 하자 집계·누적 통계 엔드포인트가 아직 없어(점검/하자 모듈에 목록 조회 API 미구현) feature 로컬 목.
export function getFacilityOverview(facilityId: number): FacilityOverview {
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
