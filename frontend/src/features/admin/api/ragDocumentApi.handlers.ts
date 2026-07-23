import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import type { RagDocument } from '../ragDocument.types';

// GET/POST /api/admin/rag-documents MSW 목 — 백엔드 계약(RagDocumentResponse)과 동일 형태.
// 업로드·재임베딩 모두 이 레포의 실제 구현처럼 동기 처리(성공 시 즉시 DONE)로 응답한다 — Java가
// 같은 요청 안에서 AI 서버 호출까지 마치고 최종 embeddingStatus를 반환하는 설계와 맞춘다(#22 handoff).

let ragDocumentSeq = 3;
let mockRagDocuments: RagDocument[] = [
  {
    id: 1,
    title: '시설물의 안전관리에 관한 특별법',
    sourceType: 'LAW',
    targetCollection: 'REGULATIONS',
    effectiveDate: '2026-01-01',
    publisher: '국토교통부',
    authoredAt: null,
    verificationStatus: null,
    embeddingStatus: 'DONE',
    chunkCount: 42,
    embeddedAt: '2026-07-10T09:00:00Z',
    createdAt: '2026-07-10T08:55:00Z',
  },
  {
    id: 2,
    title: '균열 하자 보수 지침',
    sourceType: 'GUIDELINE',
    targetCollection: 'DEFECT_KB',
    effectiveDate: null,
    publisher: null,
    authoredAt: '2026-06-01',
    verificationStatus: 'VERIFIED',
    embeddingStatus: 'FAILED',
    chunkCount: null,
    embeddedAt: null,
    createdAt: '2026-07-11T10:00:00Z',
  },
];

export const ragDocumentHandlers = [
  http.get('/api/admin/rag-documents', () => {
    const body: ApiResponse<RagDocument[]> = { success: true, data: mockRagDocuments };
    return HttpResponse.json(body);
  }),

  http.post('/api/admin/rag-documents', async ({ request }) => {
    const formData = await request.formData();
    const title = String(formData.get('title') ?? '');
    if (!title.trim()) {
      const body: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'INVALID_INPUT', message: '제목은 필수입니다.' },
      };
      return HttpResponse.json(body, { status: 400 });
    }

    const now = new Date().toISOString();
    const created: RagDocument = {
      id: ragDocumentSeq++,
      title,
      sourceType: (formData.get('sourceType') as RagDocument['sourceType']) ?? 'LAW',
      targetCollection:
        (formData.get('targetCollection') as RagDocument['targetCollection']) ?? 'REGULATIONS',
      effectiveDate: (formData.get('effectiveDate') as string) || null,
      publisher: (formData.get('publisher') as string) || null,
      authoredAt: (formData.get('authoredAt') as string) || null,
      verificationStatus: null,
      embeddingStatus: 'DONE',
      chunkCount: 12,
      embeddedAt: now,
      createdAt: now,
    };
    mockRagDocuments = [created, ...mockRagDocuments];

    const body: ApiResponse<RagDocument> = { success: true, data: created };
    return HttpResponse.json(body, { status: 201 });
  }),

  http.post('/api/admin/rag-documents/:id/re-embed', ({ params }) => {
    const id = Number(params.id);
    const target = mockRagDocuments.find((doc) => doc.id === id);
    if (!target) {
      const body: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'RAG_DOCUMENT_NOT_FOUND', message: 'RAG 문서를 찾을 수 없습니다.' },
      };
      return HttpResponse.json(body, { status: 404 });
    }

    target.embeddingStatus = 'DONE';
    target.chunkCount = (target.chunkCount ?? 0) + 1;
    target.embeddedAt = new Date().toISOString();

    const body: ApiResponse<RagDocument> = { success: true, data: target };
    return HttpResponse.json(body);
  }),
];
