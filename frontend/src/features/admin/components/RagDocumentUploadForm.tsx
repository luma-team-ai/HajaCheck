import { useState } from 'react';
import type { FormEvent } from 'react';
import { Button } from '../../../shared/components/Button';
import {
  SOURCE_TYPE_LABEL,
  SOURCE_TYPE_OPTIONS,
  TARGET_COLLECTION_LABEL,
  TARGET_COLLECTION_OPTIONS,
} from '../ragDocument.constants';
import type { RagDocumentSourceType, RagDocumentUploadPayload, RagTargetCollection } from '../ragDocument.types';

interface RagDocumentUploadFormProps {
  // 반환값은 사용하지 않는다(성공 시 폼 리셋만) — 훅의 mutateAsync가 그대로 넘어올 수 있게 unknown으로 둔다.
  onSubmit: (payload: RagDocumentUploadPayload) => Promise<unknown>;
  isSubmitting: boolean;
  submitErrorMessage?: string;
}

const INPUT_CLASS =
  'w-full rounded-full border border-border bg-surface px-4 py-3 text-sm text-text-default placeholder:text-text-muted focus:outline-none focus-visible:ring-1 focus-visible:ring-primary';
const LABEL_CLASS = 'text-xs font-medium tracking-wide text-text-muted';

// RAG 문서 업로드 폼 — #22/HAJA-35. 법규(LAW)/지침(GUIDELINE) PDF를 업로드하면 서버가 텍스트를
// 추출해 즉시 임베딩까지 실행한다(동기 처리, #22 handoff) — 제출 버튼은 그 응답을 기다리는 동안
// 비활성화된다.
export function RagDocumentUploadForm({
  onSubmit,
  isSubmitting,
  submitErrorMessage,
}: RagDocumentUploadFormProps) {
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState('');
  const [sourceType, setSourceType] = useState<RagDocumentSourceType>('LAW');
  const [targetCollection, setTargetCollection] = useState<RagTargetCollection>('REGULATIONS');
  const [publisher, setPublisher] = useState('');
  const [effectiveDate, setEffectiveDate] = useState('');
  const [authoredAt, setAuthoredAt] = useState('');
  const [touched, setTouched] = useState(false);

  const fileValid = file !== null;
  const titleValid = title.trim().length > 0;
  const formValid = fileValid && titleValid;

  function resetForm() {
    setFile(null);
    setTitle('');
    setSourceType('LAW');
    setTargetCollection('REGULATIONS');
    setPublisher('');
    setEffectiveDate('');
    setAuthoredAt('');
    setTouched(false);
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setTouched(true);
    if (!formValid || file === null) {
      return;
    }
    onSubmit({
      file,
      title: title.trim(),
      sourceType,
      targetCollection,
      publisher: publisher.trim() || undefined,
      effectiveDate: effectiveDate || undefined,
      authoredAt: authoredAt || undefined,
    })
      .then(resetForm)
      // catch만 해서 콘솔에 unhandled rejection이 찍히지 않게 한다 — 실패 메시지는
      // submitErrorMessage(mutation.error)로 아래에 표시된다(CreateUserModal과 동일 패턴).
      .catch(() => {});
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-6 rounded-[20px] border border-border bg-surface p-6 sm:p-8">
      <div>
        <h2 className="m-0 text-lg font-bold text-heading">RAG 문서 업로드</h2>
        <p className="mt-1 text-sm text-text-muted">
          법규·지침 PDF를 업로드하면 텍스트를 추출해 즉시 임베딩 파이프라인을 실행합니다.
        </p>
      </div>

      <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
        <div className="flex flex-col gap-2 sm:col-span-2">
          <label htmlFor="rag-doc-file" className={LABEL_CLASS}>
            PDF 파일
          </label>
          <input
            id="rag-doc-file"
            type="file"
            accept="application/pdf"
            className={INPUT_CLASS}
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
          />
          {touched && !fileValid && <p className="m-0 text-xs text-danger">PDF 파일을 선택해 주세요.</p>}
        </div>

        <div className="flex flex-col gap-2 sm:col-span-2">
          <label htmlFor="rag-doc-title" className={LABEL_CLASS}>
            제목
          </label>
          <input
            id="rag-doc-title"
            type="text"
            className={INPUT_CLASS}
            placeholder="예: 시설물의 안전관리에 관한 특별법"
            value={title}
            onChange={(event) => setTitle(event.target.value)}
          />
          {touched && !titleValid && <p className="m-0 text-xs text-danger">제목은 필수입니다.</p>}
        </div>

        <div className="flex flex-col gap-2">
          <label htmlFor="rag-doc-source-type" className={LABEL_CLASS}>
            출처 유형
          </label>
          <select
            id="rag-doc-source-type"
            className={INPUT_CLASS}
            value={sourceType}
            onChange={(event) => setSourceType(event.target.value as RagDocumentSourceType)}
          >
            {SOURCE_TYPE_OPTIONS.map((option) => (
              <option key={option} value={option}>
                {SOURCE_TYPE_LABEL[option]}
              </option>
            ))}
          </select>
        </div>

        <div className="flex flex-col gap-2">
          <label htmlFor="rag-doc-target-collection" className={LABEL_CLASS}>
            임베딩 대상 컬렉션
          </label>
          <select
            id="rag-doc-target-collection"
            className={INPUT_CLASS}
            value={targetCollection}
            onChange={(event) => setTargetCollection(event.target.value as RagTargetCollection)}
          >
            {TARGET_COLLECTION_OPTIONS.map((option) => (
              <option key={option} value={option}>
                {TARGET_COLLECTION_LABEL[option]}
              </option>
            ))}
          </select>
        </div>

        <div className="flex flex-col gap-2">
          <label htmlFor="rag-doc-publisher" className={LABEL_CLASS}>
            발행 기관 (선택)
          </label>
          <input
            id="rag-doc-publisher"
            type="text"
            className={INPUT_CLASS}
            placeholder="예: 국토교통부"
            value={publisher}
            onChange={(event) => setPublisher(event.target.value)}
          />
        </div>

        <div className="flex flex-col gap-2">
          <label htmlFor="rag-doc-effective-date" className={LABEL_CLASS}>
            시행일 (선택, 법규만)
          </label>
          <input
            id="rag-doc-effective-date"
            type="date"
            className={INPUT_CLASS}
            value={effectiveDate}
            onChange={(event) => setEffectiveDate(event.target.value)}
          />
        </div>

        <div className="flex flex-col gap-2">
          <label htmlFor="rag-doc-authored-at" className={LABEL_CLASS}>
            작성일 (선택, 하자 지식만)
          </label>
          <input
            id="rag-doc-authored-at"
            type="date"
            className={INPUT_CLASS}
            value={authoredAt}
            onChange={(event) => setAuthoredAt(event.target.value)}
          />
        </div>
      </div>

      {submitErrorMessage && (
        <p role="alert" className="m-0 text-sm text-danger">
          {submitErrorMessage}
        </p>
      )}

      <div className="flex justify-end">
        <Button type="submit" variant="primary" size="lg" disabled={isSubmitting} className="w-full sm:w-auto">
          {isSubmitting ? '업로드 중...' : '업로드 및 임베딩 실행'}
        </Button>
      </div>
    </form>
  );
}
