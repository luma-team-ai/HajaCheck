import { useState } from 'react';
import { TableFooterPagination } from '../../../shared/components/TableFooterPagination/TableFooterPagination';
import { RagDocumentStatsCard } from '../components/RagDocumentStatsCard';
import { RagDocumentTable } from '../components/RagDocumentTable';
import { RagDocumentUploadForm } from '../components/RagDocumentUploadForm';
import { useReEmbedRagDocument } from '../hooks/useReEmbedRagDocument';
import { useRagDocuments } from '../hooks/useRagDocuments';
import { useUploadRagDocument } from '../hooks/useUploadRagDocument';
import { TARGET_COLLECTION_LABEL, TARGET_COLLECTION_OPTIONS } from '../ragDocument.constants';
import { RefreshIcon } from '../components/icons/RefreshIcon';
import { Button } from '../../../shared/components/Button';

// AI-server가 실제로 쓰는 임베딩 모델(ai-server/ai/core/embeddings.py DEFAULT_EMBEDDING_MODEL,
// rag_ingest.py EMBEDDING_MODEL) — 두 곳 다 하드코딩된 상수라 여기서도 같은 방식으로 표시만 한다
// (배포 환경변수 EMBEDDING_MODEL로 바뀌면 이 문구도 같이 갱신 필요, 후속: 헬스체크 응답에 실어 동기화).
const EMBEDDING_MODEL_LABEL = 'BGE-m3';
const DEFAULT_PAGE_SIZE = 10;

// 플랫폼 관리자 > RAG 문서 관리 — #22/HAJA-35(GitHub #22, Jira HAJA-35), PRD FR-8-B.
// 헤더(브레드크럼)·사이드바는 PlatformAdminShellRoute → AppLayout이 담당하므로 이 페이지는 CONTENT
// 영역만 그린다. 실제 인가는 백엔드(/api/admin/** → hasRole(ADMIN))가 최종 방어선이고, 라우트의
// PlatformAdminRoute는 잘못된 화면을 감추기 위한 UX 가드일 뿐이다(PlanQuotaPage와 동일 원칙).
export function RagDocumentsPage() {
  const { data: rawDocuments, isLoading, isError, refetch } = useRagDocuments();
  const { uploadDocument, isPending: isUploading, error: uploadError, resetError } =
    useUploadRagDocument();
  const { reEmbed, pendingId: reEmbedPendingId, error: reEmbedError } = useReEmbedRagDocument();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [showBatchNotice, setShowBatchNotice] = useState(false);

  const documents = rawDocuments ?? [];
  const totalPages = Math.max(1, Math.ceil(documents.length / pageSize));
  const pagedDocuments = documents.slice((page - 1) * pageSize, page * pageSize);

  function handlePageSizeChange(size: number) {
    setPageSize(size);
    setPage(1);
  }

  return (
    <div className="flex min-h-full flex-col gap-6 bg-surface-muted p-6 sm:p-8">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="m-0 text-2xl font-bold text-heading">RAG 문서 관리</h1>
          <p className="mt-2 max-w-2xl text-sm text-text-muted">
            법규·지침 PDF를 업로드해 RAG 챗봇 임베딩 파이프라인을 실행합니다. 재임베딩은 이 화면의
            명시적 액션으로만 실행되며 자동으로 실행되지 않습니다.
          </p>
        </div>
        <div className="flex flex-col items-end gap-2">
          {/* 배치 재임베딩 API가 아직 없어(문서별 개별 재임베딩만 존재) 클릭은 받되 실제 배치를
              실행하지 않고 준비 중 안내만 띄운다 — 눌러도 반응이 없는(비활성으로 죽어 보이는)
              버튼 대신, 눌렀을 때 정직하게 상태를 알려주는 쪽을 택했다. 색상은 Figma 원본대로
              흰 배경+테두리(secondary) — 이전에 검정(primary)으로 반대로 넣었던 걸 바로잡음. */}
          <Button
            type="button"
            variant="secondary"
            size="md"
            className="rounded-full shadow-sm"
            onClick={() => setShowBatchNotice(true)}
          >
            <RefreshIcon />
            재임베딩 배치 실행
          </Button>
          {showBatchNotice && (
            <p className="m-0 text-xs text-text-muted" role="status">
              배치 재임베딩 기능은 아직 준비 중입니다. 지금은 문서별 재임베딩만 가능합니다.
            </p>
          )}
        </div>
      </div>

      <RagDocumentStatsCard documents={documents} isLoading={isLoading} isError={isError} />

      {/* AI-server 대상 컬렉션 2종(REGULATIONS/DEFECT_KB) + 임베딩 모델 안내 — Figma의 단일
          "컬렉션: haja_legal" 칩은 이 시스템의 실제 컬렉션 구조(2개)와 맞지 않아 그대로 옮기지
          않고, 실제 값(ragDocument.constants TARGET_COLLECTION_LABEL)으로 대체했다. */}
      <div className="inline-flex w-fit items-center gap-2 rounded-md bg-surface-muted px-3 py-1.5 text-xs font-semibold text-text-muted">
        <span>대상 컬렉션: {TARGET_COLLECTION_OPTIONS.map((option) => TARGET_COLLECTION_LABEL[option]).join(' · ')}</span>
        <span aria-hidden>·</span>
        <span>임베딩 모델: {EMBEDDING_MODEL_LABEL}</span>
      </div>

      <RagDocumentUploadForm
        onSubmit={(payload) => {
          resetError();
          return uploadDocument(payload);
        }}
        isSubmitting={isUploading}
        submitErrorMessage={uploadError?.message}
      />

      <div className="rounded-[20px] border border-border bg-surface p-6 sm:p-8">
        <h2 className="m-0 mb-4 text-lg font-bold text-heading">업로드된 문서</h2>
        {reEmbedError && (
          <p role="alert" className="m-0 mb-4 text-sm text-danger">
            {reEmbedError.message}
          </p>
        )}
        <RagDocumentTable
          documents={pagedDocuments}
          isLoading={isLoading}
          isError={isError}
          onRetry={() => void refetch()}
          // catch만 해서 콘솔에 unhandled rejection이 찍히지 않게 한다 — 실패 메시지는 위
          // reEmbedError로 표시된다(RagDocumentUploadForm과 동일 패턴, code-review P1).
          onReEmbed={(id) => void reEmbed(id).catch(() => {})}
          reEmbedPendingId={reEmbedPendingId}
        />

        {/* 서버 페이지네이션 API가 없어(목록 전체를 한 번에 받아옴) 이미 불러온 documents를
            화면에서 나눠 보여준다 — AdminUsersPage(서버 페이지네이션)와 달리 page/size를 서버에
            보내지 않는다. -mx/-mb로 카드 padding을 상쇄해 TableFooterPagination이 원래 설계대로
            카드 하단에 딱 붙게 한다(AdminUsersPage는 카드 자체에 padding이 없어 이 보정이 불필요). */}
        {!isLoading && !isError && documents.length > 0 && (
          <div className="-mx-6 -mb-6 mt-4 sm:-mx-8 sm:-mb-8">
            <TableFooterPagination
              pageSize={pageSize}
              onPageSizeChange={handlePageSizeChange}
              currentPage={page}
              totalPages={totalPages}
              totalItems={documents.length}
              onPageChange={setPage}
            />
          </div>
        )}
      </div>
    </div>
  );
}
