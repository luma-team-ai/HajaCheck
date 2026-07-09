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
  defects: [
    {
      id: 1,
      type: '균열',
      grade: 'C',
      status: '신규',
      confidence: 0.92,
      bbox: { x: 0.12, y: 0.3, width: 0.18, height: 0.08 },
    },
    {
      id: 2,
      type: '박리박락',
      grade: 'B',
      status: '신규',
      confidence: 0.81,
      bbox: { x: 0.55, y: 0.42, width: 0.12, height: 0.15 },
    },
    {
      id: 3,
      type: '누수백태',
      grade: 'D',
      status: '검수확정',
      confidence: 0.67,
      bbox: { x: 0.3, y: 0.6, width: 0.25, height: 0.1 },
    },
    {
      id: 4,
      type: '철근노출',
      grade: 'E',
      status: '신규',
      confidence: 0.58,
      bbox: { x: 0.7, y: 0.15, width: 0.1, height: 0.1 },
    },
    {
      id: 5,
      type: '도장손상',
      grade: 'A',
      status: '조치완료',
      confidence: 0.45,
      bbox: { x: 0.05, y: 0.75, width: 0.2, height: 0.08 },
    },
  ],
};
