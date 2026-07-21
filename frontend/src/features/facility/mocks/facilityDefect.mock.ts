import { buildDefectImagePlaceholder } from '../utils/defectImagePlaceholder';
import type {
  FacilityDefectActivityLogItem,
  FacilityDefectAiExplanation,
  FacilityDefectDetail,
} from '../types';

// dev-04-02(Figma "hajaCheck Defect Detail") 캡처 기준 예제 하자 — 강남 오피스타워 A동(facility id=1)
// imageUrl은 "원본"(마킹 없는 원본 사진) 기준 — "오버레이" 탭의 빨간 마킹 레이어는
// FacilityDefectImagePanel이 buildDefectOverlayMarkingImage()로 별도 absolute 레이어로 얹는다.
export const mockFacilityDefectDetail: FacilityDefectDetail = {
  id: 101,
  facilityId: 1,
  facilityName: '강남 오피스타워 A동',
  defectType: '균열',
  grade: 'E',
  confidencePercent: 94,
  widthMm: 0.8,
  lengthM: 2.4,
  foundCycle: 8,
  foundAt: '2026-06-21',
  location: '외벽 동측 12층 부근',
  assigneeName: '김검수',
  status: 'ACTION_PENDING',
  imageUrl: buildDefectImagePlaceholder('원본 이미지'),
};

export const mockFacilityDefectActivityLog: FacilityDefectActivityLogItem[] = [
  { id: 1, message: '이점검 님이 등급을 D→E로 수정', occurredAtLabel: '6.22' },
  { id: 2, message: 'AI 탐지 등록', occurredAtLabel: '6.21' },
];

export const mockFacilityDefectAiExplanation: FacilityDefectAiExplanation = {
  diagnosis: '구조적 스트레스로 인한 진행성 균열로 판단됩니다.',
  recommendedAction:
    '현재 폭 0.8mm로 허용 기준을 초과하였으며, 철근 부식을 방지하기 위해 에폭시 주입 공법을 통한 긴급 보수가 권장됩니다.',
};