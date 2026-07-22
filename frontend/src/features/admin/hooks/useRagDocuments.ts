import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { ragDocumentApi } from '../api/ragDocumentApi';
import type { RagDocument } from '../ragDocument.types';

// RAG 문서 목록 조회 — 업로드/재임베딩이 동기 처리(#22 handoff)라 폴링 없이 mutation 성공 시
// invalidateQueries만으로 최신 상태가 반영된다(useAdminUsers와 동일 전략).
export function useRagDocuments() {
  return useQuery<RagDocument[], ApiError>({
    queryKey: ['admin', 'rag-documents'],
    queryFn: () => ragDocumentApi.list().then((res) => res.data),
  });
}
