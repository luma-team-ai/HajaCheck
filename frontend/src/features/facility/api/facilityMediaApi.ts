import { api } from '../../../shared/api/axios';
import { totalBytesOf, uploadTimeoutMs } from '../../../shared/api/timeouts';
import type { FacilityPhoto } from '../types';

// 시설물 대표 사진 업로드/조회(#652, 백엔드 #1017/#632 완료) — inspection/api/mediaApi.ts와
// 동일 컨벤션(필드명 "files"로 다건 append, 백엔드 @RequestParam("files") List<MultipartFile>와 정합).
// 시설물 생성 폼은 항상 사진 0장에서 시작하므로 누적 4장 제한(FACILITY_PHOTO_COUNT_EXCEEDED)은
// 사실상 "이번에 고르는 파일 4장 초과"만 신경 쓰면 된다(핸드오프 §백엔드 계약).
export const facilityMediaApi = {
  upload: (facilityId: number, files: File[], onUploadProgress?: (percent: number) => void) => {
    const formData = new FormData();
    files.forEach((file) => formData.append('files', file));

    return api.post<FacilityPhoto[]>(`/facilities/${facilityId}/media`, formData, {
      // 파일 크기에 비례한 업로드 상한(#1598) — mediaApi.upload와 동일 근거(timeouts.ts).
      timeout: uploadTimeoutMs(totalBytesOf(files)),
      onUploadProgress: (event) => {
        if (!onUploadProgress || !event.total) return;
        onUploadProgress(Math.round((event.loaded / event.total) * 100));
      },
    });
  },
  list: (facilityId: number) => api.get<FacilityPhoto[]>(`/facilities/${facilityId}/media`),
};
