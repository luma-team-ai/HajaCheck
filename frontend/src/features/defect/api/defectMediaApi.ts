import { api } from '../../../shared/api/axios';

// 하자 상세 모달 "조치 후 사진 업로드" 응답 — inspection feature의 Media 타입 중 카드/모달에
// 필요한 최소 필드만 로컬로 재정의한다(feature 간 직접 import 금지, React_코드_컨벤션.md §1).
export interface DefectActionPhoto {
  id: number;
  thumbnailUrl: string;
}

// 조치 후 사진 업로드 — inspection feature의 mediaApi.ts(useUploadMedia)와 동일 패턴을 defect
// feature 안에 자체 복제한다. POST /api/inspections/{id}/media 엔드포인트를 그대로 재사용하며,
// 하자 전용 신규 첨부 엔드포인트는 만들지 않기로 확정됐다(2026-07-24 사용자 결정,
// contract.md §엔드포인트 매핑 ③파일 업로드).
export const defectMediaApi = {
  uploadActionPhoto: (
    inspectionId: number,
    file: File,
    onUploadProgress?: (percent: number) => void,
  ) => {
    const formData = new FormData();
    formData.append('files', file);

    return api.post<DefectActionPhoto[]>(`/inspections/${inspectionId}/media`, formData, {
      onUploadProgress: (event) => {
        if (!onUploadProgress || !event.total) return;
        onUploadProgress(Math.round((event.loaded / event.total) * 100));
      },
    });
  },
};
