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
  // 문서별 최초 EMBEDDING 관측 시각(#1393 리뷰 2차 P2) — 훅 인스턴스 하나에 공유 ref 하나를 쓰면
  // 오래 EMBEDDING인 문서 하나 때문에 나중에 업로드된 다른 문서까지 상한 계산이 뒤섞여, 상한 도달
  // 시 목록 전체 폴링이 멈춰버린다(신규 업로드 문서의 자동 갱신까지 정지). 문서 id별로 관측 시각을
  // 따로 들고, 더는 EMBEDDING이 아닌 문서의 항목은 제거해 메모리가 계속 늘지 않게 한다.
  const pollingStartedAtByDocIdRef = useRef<Map<number, number>>(new Map());

  return useQuery<RagDocument[], ApiError>({
    queryKey: ['admin', 'rag-documents'],
    queryFn: () => ragDocumentApi.list().then((res) => res.data),
    refetchInterval: (query) => {
      const documents = query.state.data;
      const embeddingDocs = documents?.filter((doc) => doc.embeddingStatus === 'EMBEDDING') ?? [];
      const pollingStartedAtByDocId = pollingStartedAtByDocIdRef.current;

      if (embeddingDocs.length === 0) {
        pollingStartedAtByDocId.clear();
        return false;
      }

      const now = Date.now();
      const embeddingDocIds = new Set(embeddingDocs.map((doc) => doc.id));
      for (const docId of pollingStartedAtByDocId.keys()) {
        if (!embeddingDocIds.has(docId)) {
          pollingStartedAtByDocId.delete(docId);
        }
      }

      const stillWithinLimit = embeddingDocs.some((doc) => {
        const startedAt = pollingStartedAtByDocId.get(doc.id) ?? now;
        pollingStartedAtByDocId.set(doc.id, startedAt);
        return now - startedAt < EMBEDDING_POLL_MAX_DURATION_MS;
      });
      if (!stillWithinLimit) {
        return false;
      }
      return EMBEDDING_POLL_INTERVAL_MS;
    },
  });
}
