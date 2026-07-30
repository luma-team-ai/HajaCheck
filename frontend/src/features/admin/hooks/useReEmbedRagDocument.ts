import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { ragDocumentApi } from '../api/ragDocumentApi';
import type { RagDocument } from '../ragDocument.types';

// 재임베딩 — 명시적 관리자 액션으로만 트리거(#22 handoff, PRD "재임베딩은 명시적 배치 잡으로 분리").
// mutation.variables로 어떤 문서를 재임베딩 중인지 표에서 행 단위 로딩 표시에 사용한다.
export function useReEmbedRagDocument() {
  const queryClient = useQueryClient();

  const mutation = useMutation<RagDocument, ApiError, number>({
    mutationFn: (id) => ragDocumentApi.reEmbed(id).then((res) => res.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'rag-documents'] });
    },
  });

  return {
    reEmbed: mutation.mutateAsync,
    pendingId: mutation.isPending ? mutation.variables : undefined,
    error: mutation.error,
  };
}
