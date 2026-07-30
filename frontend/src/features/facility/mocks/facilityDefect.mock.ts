import { buildDefectImagePlaceholder } from '../utils/defectImagePlaceholder';
// 활동 이력은 정본 ActivityHistoryPanel(GET /api/defects/{id}/revisions)로 대체되며(#1351), 그 응답
// 타입 DefectRevision은 defect feature 소유다 — 목 데이터 모양만 그 계약과 맞추기 위해 타입을 가져온다.
import type { DefectRevision } from '../../defect/types';
import type { FacilityDefectAiExplanation, FacilityDefectDetailResponse } from '../types';

// dev-04-02(Figma "hajaCheck Defect Detail") 캡처 기준 예제 하자 — 강남 오피스타워 A동(facility id=1).
// 실 백엔드 GET /api/defects/{id}(DefectResponse) 응답 원본(raw) 모양 그대로 목을 구성한다 —
// facilityDefectApi.getDetail이 이 값을 FacilityDefectDetail로 매핑한다(mm→m 변환·confidence*100 등).
// imageUrl은 "원본"(마킹 없는 원본 사진) 기준 — "오버레이" 탭의 빨간 마킹 레이어는
// FacilityDefectImagePanel이 buildDefectOverlayMarkingImage()로 별도 absolute 레이어로 얹는다.
export const mockFacilityDefectDetailResponse: FacilityDefectDetailResponse = {
  id: 101,
  inspectionId: 8,
  facilityId: 1,
  facilityName: '강남 오피스타워 A동',
  location: '외벽 동측 12층 부근',
  assigneeName: '김검수',
  foundCycle: 8,
  typeLabel: '균열',
  grade: 'E',
  status: 'CONFIRMED',
  confidence: 0.94,
  crackWidthMm: 0.8,
  crackLengthMm: 2400,
  imageUrl: buildDefectImagePlaceholder('원본 이미지'),
  createdAt: '2026-06-21T09:00:00.000Z',
};

export const mockFacilityDefectRevisions: DefectRevision[] = [
  {
    id: 2,
    revisedBy: 1,
    fieldChanged: 'grade',
    oldValue: 'D',
    newValue: 'E',
    reason: null,
    createdAt: '2026-06-22T09:00:00.000Z',
  },
  {
    id: 1,
    revisedBy: 1,
    fieldChanged: 'status',
    oldValue: 'DETECTED',
    newValue: 'CONFIRMED',
    reason: null,
    createdAt: '2026-06-21T09:00:00.000Z',
  },
];

export const mockFacilityDefectAiExplanation: FacilityDefectAiExplanation = {
  diagnosis: '구조적 스트레스로 인한 진행성 균열로 판단됩니다.',
  recommendedAction:
    '현재 폭 0.8mm로 허용 기준을 초과하였으며, 철근 부식을 방지하기 위해 에폭시 주입 공법을 통한 긴급 보수가 권장됩니다.',
};
