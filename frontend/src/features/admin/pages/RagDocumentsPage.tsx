import { RagDocumentTable } from '../components/RagDocumentTable';
import { RagDocumentUploadForm } from '../components/RagDocumentUploadForm';
import { useReEmbedRagDocument } from '../hooks/useReEmbedRagDocument';
import { useRagDocuments } from '../hooks/useRagDocuments';
import { useUploadRagDocument } from '../hooks/useUploadRagDocument';

// 플랫폼 관리자 > RAG 문서 관리 — #22/HAJA-35(GitHub #22, Jira HAJA-35), PRD FR-8-B.
// 헤더(브레드크럼)·사이드바는 PlatformAdminShellRoute → AppLayout이 담당하므로 이 페이지는 CONTENT
// 영역만 그린다. 실제 인가는 백엔드(/api/admin/** → hasRole(ADMIN))가 최종 방어선이고, 라우트의
// PlatformAdminRoute는 잘못된 화면을 감추기 위한 UX 가드일 뿐이다(PlanQuotaPage와 동일 원칙).
export function RagDocumentsPage() {
  const { data: documents, isLoading, isError, refetch } = useRagDocuments();
  const { uploadDocument, isPending: isUploading, error: uploadError, resetError } =
    useUploadRagDocument();
  const { reEmbed, pendingId: reEmbedPendingId } = useReEmbedRagDocument();

  return (
    <div className="flex min-h-full flex-col gap-6 bg-surface-muted p-6 sm:p-8">
      <div>
        <h1 className="m-0 text-2xl font-bold text-heading">RAG 문서 관리</h1>
        <p className="mt-2 max-w-2xl text-sm text-text-muted">
          법규·지침 PDF를 업로드해 RAG 챗봇 임베딩 파이프라인을 실행합니다. 재임베딩은 이 화면의
          명시적 액션으로만 실행되며 자동으로 실행되지 않습니다.
        </p>
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
        <RagDocumentTable
          documents={documents ?? []}
          isLoading={isLoading}
          isError={isError}
          onRetry={() => void refetch()}
          onReEmbed={(id) => void reEmbed(id)}
          reEmbedPendingId={reEmbedPendingId}
        />
      </div>
    </div>
  );
}
