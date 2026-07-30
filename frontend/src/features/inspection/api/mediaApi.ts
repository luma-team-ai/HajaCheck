import { api } from '../../../shared/api/axios';
import type { Media } from '../types';

// 촬영 데이터(이미지) 업로드 — API 명세서 v0.3 AP-005, POST /api/inspections/{id}/media(실 연동).
// 백엔드 @RequestParam("files") List<MultipartFile>와 정합(같은 필드명 "files"로 다건 append).
export const mediaApi = {
  upload: (inspectionId: number, files: File[], onUploadProgress?: (percent: number) => void) => {
    const formData = new FormData();
    files.forEach((file) => formData.append('files', file));

    return api.post<Media[]>(`/inspections/${inspectionId}/media`, formData, {
      onUploadProgress: (event) => {
        if (!onUploadProgress || !event.total) return;
        onUploadProgress(Math.round((event.loaded / event.total) * 100));
      },
    });
  },
};
