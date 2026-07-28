import { api } from '../../../shared/api/axios';
import type {
  FacilityDefectActivityLogItem,
  FacilityDefectDetail,
  FacilityDefectDetailResponse,
} from '../types';

// crackLengthMm(mm)를 화면 표시 단위(m)로 변환 — DefectResponse는 mm 단위로만 내려준다.
const MM_PER_M = 1000;

function toFacilityDefectDetail(raw: FacilityDefectDetailResponse): FacilityDefectDetail {
  return {
    id: raw.id,
    inspectionId: raw.inspectionId,
    facilityId: raw.facilityId,
    facilityName: raw.facilityName,
    defectType: raw.typeLabel,
    grade: raw.grade,
    confidencePercent: raw.confidence * 100,
    widthMm: raw.crackWidthMm,
    lengthM: raw.crackLengthMm == null ? null : raw.crackLengthMm / MM_PER_M,
    foundCycle: raw.foundCycle,
    foundAt: raw.createdAt.slice(0, 10),
    location: raw.location,
    assigneeName: raw.assigneeName,
    status: raw.status,
    imageUrl: raw.imageUrl,
  };
}

// 시설물 상세 하위 드릴다운 — 하자 정보 패널(dev-04-02, #489). 하자 상세 자체는 이제 실 백엔드
// GET /api/defects/{id}(HAJA-30, location/assigneeName은 #970 갭3)를 호출한다 — 식별자는
// facilityId가 아니라 defectId다(Facility.latestDefectId로 얻은 값을 FacilityDefectDetailPage가
// :defectId 라우트 파라미터로 전달, useFacilityDefectDetail(defectId) 호출부 참고).
// activity log/AI 설명(회차비교 포함)은 아직 백엔드 계약이 없어 MSW 목 전용으로 남는다(#489 스코프 밖).
export const facilityDefectApi = {
  getDetail: (defectId: string) =>
    api.get<FacilityDefectDetailResponse>(`/defects/${defectId}`).then((res) => ({
      ...res,
      data: toFacilityDefectDetail(res.data),
    })),
  getActivityLog: (facilityId: string) =>
    api.get<FacilityDefectActivityLogItem[]>(`/facilities/${facilityId}/defect-detail/activity`),
  // PATCH /api/defects/{id}/location — 검수자 사후 위치 편집(#970 갭3). 빈 문자열/공백 정규화는
  // 백엔드(Defect#updateLocation)가 처리하므로 프론트는 입력값을 그대로 전달한다. 인라인 편집 UI는
  // 이번 PR 범위 밖이라(조회 경로 정확성 우선) 클라이언트 함수만 먼저 추가한다.
  updateLocation: (defectId: string, location: string | null) =>
    api
      .patch<FacilityDefectDetailResponse>(`/defects/${defectId}/location`, { location })
      .then((res) => ({ ...res, data: toFacilityDefectDetail(res.data) })),
  // PATCH /api/defects/{id}/previous-defect — 회차 간 대응 하자 확정(HAJA-437). "회차 간 비교" 화면
  // 실연동은 별도 계약 확장이 필요한 후속 스코프라(#489 §5) 이번 PR은 클라이언트 함수만 추가한다.
  confirmPreviousDefect: (defectId: string, previousDefectId: number) =>
    api
      .patch<FacilityDefectDetailResponse>(`/defects/${defectId}/previous-defect`, {
        previousDefectId,
      })
      .then((res) => ({ ...res, data: toFacilityDefectDetail(res.data) })),
};
