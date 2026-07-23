import { Button } from '../../../shared/components/Button';
import { formatJoinedAt } from '../utils/formatUserDates';
import {
  EMBEDDING_STATUS_DOT_CLASS,
  EMBEDDING_STATUS_LABEL,
  EMBEDDING_STATUS_TEXT_CLASS,
  SOURCE_TYPE_LABEL,
  TARGET_COLLECTION_LABEL,
} from '../ragDocument.constants';
import type { RagDocument } from '../ragDocument.types';
import { StateRow } from './StateRow';

const COL_COUNT = 6;
const HEADER_CELL = 'px-4 py-3 text-left text-[13px] font-medium text-text-muted';
const BODY_CELL = 'px-4 py-4 align-middle';

interface RagDocumentTableProps {
  documents: RagDocument[];
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
  onReEmbed: (id: number) => void;
  reEmbedPendingId?: number;
}

function formatDateTime(value: string | null): string {
  if (!value) {
    return '-';
  }
  return new Date(value).toLocaleString('ko-KR');
}

// embeddedAt(Instant, UTC "...Z")과 createdAt(LocalDateTime, 오프셋 없음)은 백엔드 직렬화 형식이
// 서로 달라 같은 포맷터를 못 쓴다 — formatDateTime은 embeddedAt(로컬 변환 필요)에, createdAt은
// formatJoinedAt(AdminUserTable과 동일 유틸, 오프셋 없는 문자열 전용)에 맡긴다.
function formatUploadedAt(value: string): string {
  return formatJoinedAt(value);
}

// RAG 문서 목록 표 — #22/HAJA-35. 재임베딩 버튼은 상태와 무관하게 항상 노출한다(완료 문서도 재임베딩
// 가능 — "명시적 관리자 액션으로만 재실행" 원칙, PENDING/DONE/FAILED 전부 허용). EMBEDDING(처리 중)은
// 이 화면이 동기 응답을 기다리는 동안만 순간적으로 존재하는 상태라 버튼을 비활성화해 중복 요청을 막는다.
export function RagDocumentTable({
  documents,
  isLoading,
  isError,
  onRetry,
  onReEmbed,
  reEmbedPendingId,
}: RagDocumentTableProps) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[720px] border-collapse">
        <thead>
          <tr className="border-b border-border">
            <th className={`${HEADER_CELL} w-64 pl-6`}>문서명</th>
            <th className={`${HEADER_CELL} w-32`}>유형</th>
            <th className={`${HEADER_CELL} w-20 text-right`}>청크 수</th>
            <th className={`${HEADER_CELL} w-36`}>임베딩 상태</th>
            <th className={`${HEADER_CELL} w-32`}>업로드일</th>
            <th className={`${HEADER_CELL} w-28 pr-6 text-right`}>액션</th>
          </tr>
        </thead>
        <tbody>
          {isLoading && (
            <StateRow colSpan={COL_COUNT}>
              <span className="text-text-muted">불러오는 중...</span>
            </StateRow>
          )}

          {!isLoading && isError && (
            <StateRow colSpan={COL_COUNT}>
              <span className="flex flex-col items-center gap-3">
                <span className="text-danger" role="alert">
                  목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
                </span>
                <button
                  type="button"
                  className="rounded-full border border-border px-4 py-1.5 text-sm text-text-default hover:border-primary hover:text-primary"
                  onClick={onRetry}
                >
                  다시 시도
                </button>
              </span>
            </StateRow>
          )}

          {!isLoading && !isError && documents.length === 0 && (
            <StateRow colSpan={COL_COUNT}>
              <span className="text-text-muted">등록된 RAG 문서가 없습니다</span>
            </StateRow>
          )}

          {!isLoading &&
            !isError &&
            documents.map((document) => {
              const isReEmbedding =
                reEmbedPendingId === document.id || document.embeddingStatus === 'EMBEDDING';
              return (
                <tr
                  key={document.id}
                  className="border-b border-border last:border-b-0 hover:bg-surface-muted"
                >
                  <td className={`${BODY_CELL} pl-6`}>
                    <p className="text-sm font-semibold text-heading">{document.title}</p>
                    {document.publisher && (
                      <p className="text-[13px] text-text-muted">{document.publisher}</p>
                    )}
                  </td>
                  <td className={BODY_CELL}>
                    <span className="inline-flex rounded-full bg-surface-muted px-2.5 py-1 text-xs text-text-default">
                      {SOURCE_TYPE_LABEL[document.sourceType]}
                    </span>
                    <p className="mt-1 text-[13px] text-text-muted">
                      {TARGET_COLLECTION_LABEL[document.targetCollection]}
                    </p>
                  </td>
                  <td className={`${BODY_CELL} text-right font-mono text-sm text-text-default`}>
                    {document.chunkCount ?? '-'}
                  </td>
                  <td className={BODY_CELL}>
                    <span
                      className={`flex items-center gap-1.5 text-[13px] font-medium ${EMBEDDING_STATUS_TEXT_CLASS[document.embeddingStatus]}`}
                    >
                      <span
                        className={`inline-block h-1.5 w-1.5 rounded-full ${EMBEDDING_STATUS_DOT_CLASS[document.embeddingStatus]}`}
                        aria-hidden
                      />
                      {EMBEDDING_STATUS_LABEL[document.embeddingStatus]}
                    </span>
                    {document.embeddingStatus === 'DONE' && (
                      <p className="mt-0.5 text-[13px] text-text-muted">
                        {formatDateTime(document.embeddedAt)}
                      </p>
                    )}
                  </td>
                  <td className={`${BODY_CELL} text-sm text-text-default`}>
                    {formatUploadedAt(document.createdAt)}
                  </td>
                  <td className={`${BODY_CELL} pr-6 text-right`}>
                    <Button
                      type="button"
                      variant="secondary"
                      size="sm"
                      disabled={isReEmbedding}
                      onClick={() => onReEmbed(document.id)}
                    >
                      {isReEmbedding ? '재임베딩 중...' : '재임베딩'}
                    </Button>
                  </td>
                </tr>
              );
            })}
        </tbody>
      </table>
    </div>
  );
}
