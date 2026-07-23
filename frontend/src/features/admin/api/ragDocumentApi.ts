import { api } from '../../../shared/api/axios';
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
  upload: (payload: RagDocumentUploadPayload) =>
    api.post<RagDocument>('/admin/rag-documents', toRagDocumentUploadFormData(payload)),
  reEmbed: (id: number) => api.post<RagDocument>(`/admin/rag-documents/${id}/re-embed`),
};
