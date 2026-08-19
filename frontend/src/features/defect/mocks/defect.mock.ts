import type {
  Defect,
  DefectActionLogEntry,
  DefectRevision,
  InspectionDefect,
  InspectionDefectResponse,
} from '../types';
import { mapInspectionDefect } from '../utils/inspectionDefectMapper';

// HAJA-30 목록/상세 통합 테스트용 목 데이터 — 유형/등급/상태 다양화
export const mockInspectionDefectResponses: InspectionDefectResponse[] = [
  {
    id: 1,
    inspectionId: 101,
    type: 'REBAR_EXPOSURE',
    typeLabel: '철근 노출',
    grade: 'D',
    status: 'CONFIRMED',
    confidence: 0.92,
    isReviewed: true,
    bboxX: 0.1,
    bboxY: 0.2,
    bboxW: 0.3,
    bboxH: 0.15,
    crackWidthMm: null,
    crackLengthMm: null,
    areaRatio: 0.045,
    areaMm2: 4200.5, // 실측 면적 값 케이스(#1658/#1669) — 철근 노출은 카드 기준물 검출됨
    // 참고 등급 값 케이스(#1683/#1684) — 본등급(D)과 다른 값(C)으로 둬서 화면에서 두 등급이
    // 실제로 독립적인 표시라는 걸 검증한다(단순히 본등급을 복제한 게 아님).
    areaMm2ReferenceGrade: 'C',
    mediaId: 901,
    imageUrl: '/api/media/901/thumbnail',
    detailUrl: '/api/media/901/detail',
    createdAt: '2026-07-01T09:00:00.000Z',
  },
  {
    id: 2,
    inspectionId: 101,
    type: 'CRACK',
    typeLabel: '균열',
    grade: 'C',
    status: 'DETECTED',
    confidence: 0.81,
    isReviewed: false,
    bboxX: 0.4,
    bboxY: 0.5,
    bboxW: 0.2,
    bboxH: 0.1,
    crackWidthMm: 1.2,
    crackLengthMm: 45.0,
    areaRatio: null,
    areaMm2: null, // 균열은 항상 null(#1658/#1669 — SPALLING/REBAR_EXPOSURE 전용)
    areaMm2ReferenceGrade: null, // 균열은 항상 null(#1683/#1684 — areaMm2와 동일 조건)
    mediaId: 902,
    imageUrl: '/api/media/902/thumbnail',
    detailUrl: '/api/media/902/detail',
    createdAt: '2026-07-02T09:00:00.000Z',
  },
  {
    id: 3,
    inspectionId: 202,
    type: 'SPALLING',
    typeLabel: '박리·박락',
    grade: null,
    status: 'DETECTED',
    confidence: 0.65,
    isReviewed: false,
    bboxX: null,
    bboxY: null,
    bboxW: null,
    bboxH: null,
    crackWidthMm: null,
    crackLengthMm: null,
    areaRatio: null,
    areaMm2: null, // 박리박락이지만 카드 기준물 미검출 케이스(#1658/#1669) — '측정 예정' 표시 검증용
    areaMm2ReferenceGrade: null, // 카드 기준물 미검출이면 참고 등급도 null(#1683/#1684)
    // mediaId 없는 하자(HAJA-314) — 이미지 없이 조회되는 케이스를 목데이터에서도 재현.
    mediaId: null,
    imageUrl: null,
    detailUrl: null,
    createdAt: '2026-07-03T09:00:00.000Z',
  },
  {
    id: 4,
    inspectionId: 101,
    type: 'CRACK',
    typeLabel: '균열',
    grade: 'B',
    status: 'IN_PROGRESS',
    confidence: 0.87,
    isReviewed: true,
    bboxX: 0.58,
    bboxY: 0.42,
    bboxW: 0.16,
    bboxH: 0.22,
    crackWidthMm: 0.6,
    crackLengthMm: 31.5,
    areaRatio: null,
    areaMm2: null, // 균열은 항상 null(#1658/#1669)
    areaMm2ReferenceGrade: null, // 균열은 항상 null(#1683/#1684)
    mediaId: 901,
    imageUrl: '/api/media/901/thumbnail',
    detailUrl: '/api/media/901/detail',
    createdAt: '2026-07-01T09:10:00.000Z',
  },
];

export const mockInspectionDefects: InspectionDefect[] =
  mockInspectionDefectResponses.map(mapInspectionDefect);

export const mockDefects: Defect[] = mockInspectionDefects.map((defect): Defect => {
  const facility = defect.inspectionId === 101
    ? { facilityId: 1, facilityName: '강남 오피스타워 A동', facilityType: '건물' }
    : { facilityId: 3, facilityName: '한강대교 북단', facilityType: '교량' };
  return {
    id: defect.id,
    inspectionId: defect.inspectionId,
    ...facility,
    type: defect.type,
    typeLabel: defect.typeLabel,
    grade: defect.grade,
    status: defect.status,
    confidence: defect.confidence,
    reviewed: defect.reviewed,
    bboxX: defect.bboxX,
    bboxY: defect.bboxY,
    bboxW: defect.bboxW,
    bboxH: defect.bboxH,
    crackWidthMm: defect.crackWidthMm,
    crackLengthMm: defect.crackLengthMm,
    areaMm2: defect.areaMm2, // backend DefectResponse.areaMm2(#1658/#1669) — InspectionDefect에서 그대로 전달
    areaMm2ReferenceGrade: defect.areaMm2ReferenceGrade, // backend DefectResponse.areaMm2ReferenceGrade(#1683/#1684)
    imageUrl: defect.imageUrl,
    createdAt: defect.createdAt,
  };
});

// GET /api/defects/{id}/revisions 통합 테스트용 목 데이터 — id=1 하자의 상태 전이 이력(HAJA-314)
export const mockDefectRevisions: Record<number, DefectRevision[]> = {
  1: [
    {
      id: 1,
      revisedBy: 100,
      fieldChanged: 'status',
      oldValue: 'DETECTED',
      newValue: 'CONFIRMED',
      reason: null,
      createdAt: '2026-07-01T09:05:00.000Z',
    },
    {
      id: 2,
      revisedBy: 100,
      fieldChanged: 'status',
      oldValue: 'CONFIRMED',
      newValue: 'ACTION_PENDING',
      reason: null,
      createdAt: '2026-07-01T09:10:00.000Z',
    },
  ],
};

// GET /api/defects/{id}/action-logs?phase= 통합 테스트용 목 데이터(#1193/HAJA-569 백엔드,
// #1211/HAJA-574 프론트) — id=1 하자에 IN_PROGRESS 2건(등록일 select 노출 조건 충족), RESOLVED는
// 기본적으로 비워두고 각 테스트가 필요할 때 server.use()로 오버라이드한다.
export const mockDefectActionLogs: Record<number, { IN_PROGRESS: DefectActionLogEntry[]; RESOLVED: DefectActionLogEntry[] }> = {
  1: {
    IN_PROGRESS: [
      {
        id: 2,
        photoUrl: '/api/media/998/thumbnail',
        actionContent: '2차 보수 진행',
        actionDate: '2026-07-22',
        actionAssigneeId: 1,
        actionAssigneeName: '홍길동',
        createdAt: '2026-07-22T09:00:00.000Z',
      },
      {
        id: 1,
        photoUrl: '/api/media/997/thumbnail',
        actionContent: '1차 실측 완료',
        actionDate: '2026-07-20',
        actionAssigneeId: 1,
        actionAssigneeName: '홍길동',
        createdAt: '2026-07-20T09:00:00.000Z',
      },
    ],
    RESOLVED: [],
  },
};
