import { api } from '../../../shared/api/axios';
import { AI_TIMEOUT_MS, uploadTimeoutMs } from '../../../shared/api/timeouts';
import type { RagDocument, RagDocumentUploadPayload } from '../ragDocument.types';

// RAG 문서 업로드 요청을 multipart/form-data로 변환 — 계약(백엔드 RagDocumentUploadRequest) 필드명과
// 1:1(authApi.toCompanySignupFormData와 동일 패턴). 파일 파트를 포함한 라운드트립은 msw+jsdom+undici
// 환경 한계로 별도 검증한다(authApi.ts 주석과 동일 이유).
export function toRagDocumentUploadFormData(payload: RagDocumentUploadPayload): FormData {
  const formData = new FormData();
  formData.append('file', payload.file);
  formData.append('title', payload.title);
  formData.append('sourceType', payload.sourceType);
  formData.append('targetCollection', payload.targetCollection);
  if (payload.effectiveDate) {
    formData.append('effectiveDate', payload.effectiveDate);
  }
  if (payload.publisher) {
    formData.append('publisher', payload.publisher);
  }
  if (payload.authoredAt) {
    formData.append('authoredAt', payload.authoredAt);
  }
  return formData;
}

export const ragDocumentApi = {
  list: () => api.get<RagDocument[]>('/admin/rag-documents'),
  // 업로드·재임베딩은 요청 안에서 ai-server 임베딩 호출을 동기로 기다린다(백엔드
  // RagDocumentService.upload → embed() → aiProxyService.embedRagDocument, 완료 폴링만 비동기로
  // 분리). 즉 전역 30초로는 정상 임베딩이 끊긴다 — AI 상한을 처리 천장으로 두고, 업로드는 거기에
  // 파일 전송 시간을 더한다(#1598, 근거는 timeouts.ts).
  upload: (payload: RagDocumentUploadPayload) =>
    api.post<RagDocument>('/admin/rag-documents', toRagDocumentUploadFormData(payload), {
      timeout: uploadTimeoutMs(payload.file.size, AI_TIMEOUT_MS),
    }),
  reEmbed: (id: number) =>
    api.post<RagDocument>(`/admin/rag-documents/${id}/re-embed`, undefined, {
      // 재임베딩은 파일 전송이 없다(서버가 이미 가진 텍스트를 다시 임베딩) — 처리 천장만 필요.
      timeout: AI_TIMEOUT_MS,
    }),
  delete: (id: number) => api.delete<void>(`/admin/rag-documents/${id}`),
};
