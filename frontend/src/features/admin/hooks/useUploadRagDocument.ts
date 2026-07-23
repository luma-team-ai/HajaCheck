import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { ragDocumentApi } from '../api/ragDocumentApi';
import type { RagDocument, RagDocumentUploadPayload } from '../ragDocument.types';

export function useUploadRagDocument() {
  const queryClient = useQueryClient();

  const mutation = useMutation<RagDocument, ApiError, RagDocumentUploadPayload>({
    mutationFn: (payload) => ragDocumentApi.upload(payload).then((res) => res.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'rag-documents'] });
    },
  });

  return {
    uploadDocument: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
