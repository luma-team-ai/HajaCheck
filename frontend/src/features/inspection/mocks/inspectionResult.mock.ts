import type { InspectionResult } from '../types';

// ponytail: 실제 사진 대신 SVG data URI 목업 — 피그마 시안 확정 전까지 데이터 흐름 검증용
const MOCK_IMAGE_URL =
  'data:image/svg+xml;utf8,' +
  encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="1200">' +
      '<rect width="100%" height="100%" fill="#d9d9d9"/>' +
      '<text x="50%" y="50%" font-size="48" fill="#888" text-anchor="middle">샘플 점검 이미지 (mock)</text>' +
      '</svg>',
  );

export const mockInspectionResult: InspectionResult = {
  inspectionId: 1,
  media: {
    id: 1,
    imageUrl: MOCK_IMAGE_URL,
    width: 1600,
    height: 1200,
  },
  defectCode: 'DEF-0192',
  facilityName: '강남 오피스타워 A동',
  facilityType: '건물',
  status: 'AI 검수중',
  reviewedCount: 128,
  totalCount: 214,
  defects: [
    {
      id: 1,
      type: '균열',
      grade: 'C',
      status: '신규',
      confidence: 0.98,
      bbox: { x: 0.12, y: 0.3, width: 0.18, height: 0.08 },
      widthMm: 3.2,
      lengthMm: 45,
      summary: '수평 방향의 구조적 균열로 판단됨. 보수 권장.',
    },
    {
      id: 2,
      type: '박리박락',
      grade: 'B',
      status: '신규',
      confidence: 0.81,
      bbox: { x: 0.55, y: 0.42, width: 0.12, height: 0.15 },
      areaRatio: 0.08,
      summary: '콘크리트 표면 박리 영역 확대 중. 즉시 조치 필요.',
    },
    {
      id: 3,
      type: '철근노출',
      grade: 'D',
      status: '검수확정',
      confidence: 0.67,
      bbox: { x: 0.3, y: 0.6, width: 0.25, height: 0.1 },
      areaRatio: 0.05,
      summary: '철근 일부가 노출되어 부식 진행 우려. 방청 처리 검토.',
    },
    {
      id: 4,
      type: '철근노출',
      grade: 'E',
      status: '신규',
      confidence: 0.58,
      bbox: { x: 0.7, y: 0.15, width: 0.1, height: 0.1 },
      areaRatio: 0.16,
      summary: '부분적 철근 노출. 부식 방지 코팅 권장.',
    },
    {
      id: 5,
      type: '박리박락',
      grade: 'A',
      status: '조치완료',
      confidence: 0.45,
      bbox: { x: 0.05, y: 0.75, width: 0.2, height: 0.08 },
      areaRatio: 0.01,
      summary: '박리 경미. 재도장으로 해결 가능.',
    },
  ],
};
