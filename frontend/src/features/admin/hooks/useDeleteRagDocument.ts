import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { ragDocumentApi } from '../api/ragDocumentApi';

// 문서 삭제(#1394) — 파괴적 액션이라 호출 전 확인 다이얼로그를 거친다(RagDocumentsPage).
// useReEmbedRagDocument와 동일 패턴: mutation.variables로 어떤 문서를 삭제 중인지 표에서 행 단위
// 로딩 표시에 사용한다. 백엔드가 AI 서버 Chroma 청크 삭제 → DB/파일 삭제 순으로 처리해, 실패하면
// DB에는 그대로 남아 있으므로 실패 시 다시 삭제를 시도하면 된다(idempotent).
export function useDeleteRagDocument() {
  const queryClient = useQueryClient();

  const mutation = useMutation<void, ApiError, number>({
    mutationFn: (id) => ragDocumentApi.delete(id).then(() => undefined),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'rag-documents'] });
    },
  });

  return {
    deleteDocument: mutation.mutateAsync,
    pendingId: mutation.isPending ? mutation.variables : undefined,
    error: mutation.error,
  };
}
