import { useQuery } from '@tanstack/react-query';
import { useRef } from 'react';
import type { ApiError } from '../../../shared/api/types';
import { ragDocumentApi } from '../api/ragDocumentApi';
import type { RagDocument } from '../ragDocument.types';

// RAG 문서 목록 조회 — 임베딩 완료 확정이 백엔드 폴러(RagEmbeddingCompletionPoller, #1328)를 거쳐
// 비동기로 반영되므로, mutation 성공 시 invalidateQueries만으로는 EMBEDDING→DONE/FAILED 전이를
// 놓친다. 목록에 EMBEDDING 상태 문서가 남아있는 동안만 짧은 간격으로 자동 재조회하고, 없으면
// 폴링을 멈춘다(불필요한 요청 방지).
const EMBEDDING_POLL_INTERVAL_MS = 4000;

// 폴링 총 상한(#1393 리뷰 P2) — 서버측 stale 리컨사일러(RagEmbeddingStaleReconciler, 임계 5분)가
// 고착 문서를 FAILED로 정리하면 폴링은 자연히 멈추지만, 그 배치가 지연되거나 멈춘 경우까지 무한
// 재조회하지 않도록 프론트에도 안전장치를 둔다. 서버 임계보다 넉넉하게 잡는다.
const EMBEDDING_POLL_MAX_DURATION_MS = 10 * 60 * 1000;

export function useRagDocuments() {
  // EMBEDDING 문서를 처음 관측한 시각 — 상한 계산 기준. EMBEDDING이 사라지면 초기화해 다음
  // 업로드·재임베딩에서 상한이 다시 처음부터 적용되게 한다.
  const pollingStartedAtRef = useRef<number | null>(null);

  return useQuery<RagDocument[], ApiError>({
    queryKey: ['admin', 'rag-documents'],
    queryFn: () => ragDocumentApi.list().then((res) => res.data),
    refetchInterval: (query) => {
      const documents = query.state.data;
      const hasEmbeddingInProgress = documents?.some((doc) => doc.embeddingStatus === 'EMBEDDING');
      if (!hasEmbeddingInProgress) {
        pollingStartedAtRef.current = null;
        return false;
      }

      const now = Date.now();
      pollingStartedAtRef.current ??= now;
      if (now - pollingStartedAtRef.current >= EMBEDDING_POLL_MAX_DURATION_MS) {
        return false;
      }
      return EMBEDDING_POLL_INTERVAL_MS;
    },
  });
}
