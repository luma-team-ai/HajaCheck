import { EMPTY_CELL } from '../constants';
import type { RagDocument } from '../ragDocument.types';

/** 통계 카드 컨테이너 식별자 — 테스트가 카드 범위로 좁혀 조회할 때 사용 */
export const RAG_DOCUMENT_STATS_TEST_ID = 'rag-document-stats';

interface RagDocumentStatsCardProps {
  documents: RagDocument[];
  isLoading: boolean;
  isError: boolean;
}

interface RagDocumentStats {
  totalDocuments: number;
  totalChunks: number;
  embeddingDone: number;
  needsReEmbed: number;
}

function computeStats(documents: RagDocument[]): RagDocumentStats {
  return documents.reduce<RagDocumentStats>(
    (acc, document) => {
      acc.totalDocuments += 1;
      acc.totalChunks += document.chunkCount ?? 0;
      if (document.embeddingStatus === 'DONE') {
        acc.embeddingDone += 1;
      }
      // FAILED만 "재임베딩 필요"로 집계한다 — PENDING/EMBEDDING은 아직 처리 순서를 기다리거나
      // 처리 중인 정상 흐름이라 "필요(조치 대상)"로 잘못 읽히면 안 된다.
      if (document.embeddingStatus === 'FAILED') {
        acc.needsReEmbed += 1;
      }
      return acc;
    },
    { totalDocuments: 0, totalChunks: 0, embeddingDone: 0, needsReEmbed: 0 },
  );
}

// RAG 문서 관리 상단 통계 카드 — 그림자 있는 개별 카드 4개(Figma 디자인). 집계 API가 따로
// 없어(#22 diff 범위 밖) 이미 불러온 documents 목록에서 클라이언트 계산한다 — 목록이
// 페이지네이션되면 이 카드도 "현재 로드된 페이지" 기준으로 재계산해야 한다(후속: 서버 집계
// 엔드포인트로 대체 검토).
export function RagDocumentStatsCard({ documents, isLoading, isError }: RagDocumentStatsCardProps) {
  const stats = computeStats(documents);
  const showEmpty = isLoading || isError;

  return (
    <dl
      data-testid={RAG_DOCUMENT_STATS_TEST_ID}
      className="flex flex-wrap items-stretch justify-center gap-4"
    >
      <StatItem label="전체 문서" value={stats.totalDocuments} showEmpty={showEmpty} />
      <StatItem label="총 청크" value={stats.totalChunks} showEmpty={showEmpty} />
      <StatItem
        label="임베딩 완료"
        value={stats.embeddingDone}
        dotClassName="bg-[#16a34a]"
        showEmpty={showEmpty}
      />
      <StatItem
        label="재임베딩 필요"
        value={stats.needsReEmbed}
        dotClassName="bg-[#f97316]"
        labelClassName="text-[#f97316]"
        emphasize={stats.needsReEmbed > 0 && !showEmpty}
        showEmpty={showEmpty}
      />
    </dl>
  );
}

interface StatItemProps {
  label: string;
  value: number;
  dotClassName?: string;
  labelClassName?: string;
  showEmpty?: boolean;
  /** 재임베딩 필요 건수가 0보다 클 때만 카드 배경/보더를 주황으로 강조(Figma) */
  emphasize?: boolean;
}

function StatItem({
  label,
  value,
  dotClassName,
  labelClassName,
  showEmpty = false,
  emphasize = false,
}: StatItemProps) {
  return (
    <div
      className={`min-w-[160px] flex-1 rounded-[20px] border p-5 shadow-sm backdrop-blur-[10px] ${
        emphasize ? 'border-[#f97316]/30 bg-orange-50/40' : 'border-border bg-surface/90'
      }`}
    >
      <dt className={`flex items-center gap-1.5 text-xs font-medium tracking-wide ${labelClassName ?? 'text-text-muted'}`}>
        {dotClassName && (
          <span className={`inline-block h-2 w-2 rounded-full ${dotClassName}`} aria-hidden />
        )}
        {label}
      </dt>
      <dd className="mt-2">
        <span
          className={`text-3xl font-semibold ${showEmpty ? 'text-text-muted' : 'text-heading'}`}
        >
          {showEmpty ? EMPTY_CELL : value.toLocaleString('ko-KR')}
        </span>
      </dd>
    </div>
  );
}
