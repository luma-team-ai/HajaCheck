import { api } from '../../../shared/api/axios';
import type { FacilityDefectActivityLogItem, FacilityDefectDetail } from '../types';

// 시설물 상세 하자 정보 패널(dev-04-02, #489) — openapi.yaml FacilityDetailResponse가
// "계획안·미구현" 상태라 아래 엔드포인트는 전부 MSW 목 전용이다(facilityDefectApi.handlers.ts).
// "다음 단계로 전이" 버튼은 상태 mutation이 아니라 /defects/:id(하자 관리 도메인)로의 단순
// 페이지 이동이라 별도 상태 전이 API는 두지 않는다(#489 요구사항 확정).
export const facilityDefectApi = {
  getDetail: (facilityId: string) =>
    api.get<FacilityDefectDetail>(`/facilities/${facilityId}/defect-detail`),
  getActivityLog: (facilityId: string) =>
    api.get<FacilityDefectActivityLogItem[]>(`/facilities/${facilityId}/defect-detail/activity`),
};