import { api } from '../../../shared/api/axios';
import { totalBytesOf, uploadTimeoutMs } from '../../../shared/api/timeouts';
import type { Media } from '../types';

// 촬영 데이터(이미지) 업로드 — API 명세서 v0.3 AP-005, POST /api/inspections/{id}/media(실 연동).
// 백엔드 @RequestParam("files") List<MultipartFile>와 정합(같은 필드명 "files"로 다건 append).
export const mediaApi = {
  upload: (inspectionId: number, files: File[], onUploadProgress?: (percent: number) => void) => {
    const formData = new FormData();
    files.forEach((file) => formData.append('files', file));

    return api.post<Media[]>(`/inspections/${inspectionId}/media`, formData, {
      // 전역 30초 상한을 넘겨야 하는 경로(#1598) — 이 엔드포인트는 최대 50장×20MB ≈ 1000MB까지
      // 받도록 계약돼 있어(constants.ts, nginx client_max_body_size 1005m) 고정 상한으로는 정상
      // 업로드가 끊긴다. 실제 보낼 바이트 수에 비례한 상한을 준다 — 근거는 timeouts.ts.
      timeout: uploadTimeoutMs(totalBytesOf(files)),
      onUploadProgress: (event) => {
        if (!onUploadProgress || !event.total) return;
        onUploadProgress(Math.round((event.loaded / event.total) * 100));
      },
    });
  },
};
