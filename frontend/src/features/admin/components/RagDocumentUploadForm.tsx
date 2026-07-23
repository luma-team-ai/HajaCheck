import { useRef, useState } from 'react';
import type { DragEvent, FormEvent } from 'react';
import { Button } from '../../../shared/components/Button';
import { ADMIN_FORM_INPUT_CLASS, ADMIN_FORM_LABEL_CLASS } from '../adminFormClasses';
import { UploadCloudIcon } from './icons/UploadCloudIcon';
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

// 기본값 단일 소스 — useState 초기값과 resetForm() 양쪽에 7개 필드가 따로 적혀 있던 걸 하나로
// 모은다(code-review 단순화 지적). 필드 추가·기본값 변경 시 여기 한 곳만 고치면 된다.
const INITIAL_FORM_VALUES = {
  title: '',
  sourceType: 'LAW' as RagDocumentSourceType,
  targetCollection: 'REGULATIONS' as RagTargetCollection,
  publisher: '',
  effectiveDate: '',
  authoredAt: '',
};

// 컬렉션 선택에 따른 출처 유형 기본값 — REGULATIONS(법규·지침)는 실제 법령 원문이 대부분이라
// LAW, DEFECT_KB(하자 지식)는 판정 매뉴얼류가 대부분이라 GUIDELINE을 기본값으로 미리 채운다
// (관리자 매번 재선택하는 번거로움을 줄임). 강제는 아니라 출처 유형 select는 그대로 열려있어
// 실제 문서가 이 기본값과 다르면 직접 바꿀 수 있다.
const DEFAULT_SOURCE_TYPE_BY_COLLECTION: Record<RagTargetCollection, RagDocumentSourceType> = {
  REGULATIONS: 'LAW',
  DEFECT_KB: 'GUIDELINE',
};

// RAG 문서 업로드 폼 — #22/HAJA-35. 법규(LAW)/지침(GUIDELINE) PDF를 업로드하면 서버가 텍스트를
// 추출해 즉시 임베딩까지 실행한다(동기 처리, #22 handoff) — 제출 버튼은 그 응답을 기다리는 동안
// 비활성화된다.
export function RagDocumentUploadForm({
  onSubmit,
  isSubmitting,
  submitErrorMessage,
}: RagDocumentUploadFormProps) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [isDragActive, setIsDragActive] = useState(false);
  const [dropError, setDropError] = useState(false);
  const [title, setTitle] = useState(INITIAL_FORM_VALUES.title);
  const [sourceType, setSourceType] = useState<RagDocumentSourceType>(INITIAL_FORM_VALUES.sourceType);
  const [targetCollection, setTargetCollection] = useState<RagTargetCollection>(
    INITIAL_FORM_VALUES.targetCollection,
  );
  const [publisher, setPublisher] = useState(INITIAL_FORM_VALUES.publisher);
  const [effectiveDate, setEffectiveDate] = useState(INITIAL_FORM_VALUES.effectiveDate);
  const [authoredAt, setAuthoredAt] = useState(INITIAL_FORM_VALUES.authoredAt);
  const [touched, setTouched] = useState(false);

  const fileValid = file !== null;
  const titleValid = title.trim().length > 0;
  const formValid = fileValid && titleValid;

  function resetForm() {
    setFile(null);
    setTitle(INITIAL_FORM_VALUES.title);
    setSourceType(INITIAL_FORM_VALUES.sourceType);
    setTargetCollection(INITIAL_FORM_VALUES.targetCollection);
    setPublisher(INITIAL_FORM_VALUES.publisher);
    setEffectiveDate(INITIAL_FORM_VALUES.effectiveDate);
    setAuthoredAt(INITIAL_FORM_VALUES.authoredAt);
    setTouched(false);
  }

  function handleDragOver(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    setIsDragActive(true);
  }

  function handleDragLeave() {
    setIsDragActive(false);
  }

  // accept 속성은 드래그앤드롭엔 적용되지 않아(BusinessLicenseUpload와 동일 이유) 아무 파일이나
  // 들어올 수 있다 — PDF가 아니면 이전엔 아무 피드백 없이 무시해 "왜 반응이 없지"로 읽혔다
  // (PR#685 리뷰 P3). 클릭 선택 경로의 fileValid 검증 메시지와 같은 문구로 안내한다.
  function handleDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    setIsDragActive(false);
    const dropped = event.dataTransfer.files?.[0] ?? null;
    if (dropped && dropped.type !== 'application/pdf') {
      setDropError(true);
      return;
    }
    setDropError(false);
    setFile(dropped);
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

      <div className="flex flex-col gap-2">
        <div
          className={`flex cursor-pointer flex-col items-center gap-3 rounded-[20px] border-2 border-dashed px-6 py-10 text-center transition-colors ${
            isDragActive ? 'border-primary bg-surface-muted' : 'border-border'
          }`}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={handleDrop}
          // 드롭존 전체를 클릭 타깃으로 둔다 — "파일 선택" 버튼 하나(가로 폭 좁음)만 클릭 가능하면
          // 실사용 환경(브라우저 확장·플로팅 위젯 등)에 따라 그 작은 영역만 다른 레이어에 가려질
          // 여지가 있다. 버튼은 이 div로 클릭이 버블링되므로 자체 onClick은 두지 않는다(중복 실행 방지 —
          // 버튼에도 onClick을 달면 버튼 클릭 시 버튼 핸들러+버블링된 div 핸들러가 둘 다 돌아
          // input.click()이 두 번 불려 파일 선택창이 중복으로 뜬다).
          onClick={() => fileInputRef.current?.click()}
        >
          <span
            aria-hidden="true"
            className="flex h-16 w-16 items-center justify-center rounded-full bg-surface-muted text-text-muted"
          >
            <UploadCloudIcon />
          </span>
          <p className="m-0 text-xl font-medium text-heading">법규·지침 PDF를 끌어다 놓으세요</p>
          <p className="m-0 text-sm text-text-muted">PDF · 조문 경계 기준 자동 청킹</p>
          <Button type="button" variant="primary" size="md">
            파일 선택
          </Button>
          <input
            ref={fileInputRef}
            id="rag-doc-file"
            aria-label="PDF 파일"
            type="file"
            accept="application/pdf"
            className="hidden"
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
          />
        </div>

        {file && (
          <div className="flex items-center gap-2 rounded-lg bg-surface-muted px-3 py-2 text-sm">
            <span aria-hidden="true">📎</span>
            <span className="flex-1 truncate text-text-default">{file.name}</span>
            <button
              type="button"
              className="cursor-pointer border-none bg-transparent px-1 text-text-muted"
              aria-label="첨부 파일 삭제"
              onClick={() => setFile(null)}
            >
              ✕
            </button>
          </div>
        )}

        {dropError && <p className="m-0 text-xs text-danger">PDF 파일만 업로드할 수 있습니다.</p>}
        {touched && !fileValid && <p className="m-0 text-xs text-danger">PDF 파일을 선택해 주세요.</p>}
      </div>

      {/* 파일을 고르기 전엔 Figma 디자인대로 드롭존만 보인다 — 제목·출처유형 등 메타데이터
          입력은 실제 업로드에 꼭 필요한 값(title 필수)이라 없앨 순 없지만, 파일 선택 후에만
          펼쳐서 처음 보이는 화면은 디자인과 동일하게 단순하게 유지한다. */}
      {file && (
      <>
      <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
        <div className="flex flex-col gap-2 sm:col-span-2">
          <label htmlFor="rag-doc-title" className={ADMIN_FORM_LABEL_CLASS}>
            제목
          </label>
          <input
            id="rag-doc-title"
            type="text"
            className={ADMIN_FORM_INPUT_CLASS}
            placeholder="예: 시설물의 안전관리에 관한 특별법"
            value={title}
            onChange={(event) => setTitle(event.target.value)}
          />
          {touched && !titleValid && <p className="m-0 text-xs text-danger">제목은 필수입니다.</p>}
        </div>

        <div className="flex flex-col gap-2">
          <label htmlFor="rag-doc-source-type" className={ADMIN_FORM_LABEL_CLASS}>
            출처 유형
          </label>
          <select
            id="rag-doc-source-type"
            className={ADMIN_FORM_INPUT_CLASS}
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
          <label htmlFor="rag-doc-target-collection" className={ADMIN_FORM_LABEL_CLASS}>
            임베딩 대상 컬렉션
          </label>
          <select
            id="rag-doc-target-collection"
            className={ADMIN_FORM_INPUT_CLASS}
            value={targetCollection}
            onChange={(event) => {
              const next = event.target.value as RagTargetCollection;
              setTargetCollection(next);
              setSourceType(DEFAULT_SOURCE_TYPE_BY_COLLECTION[next]);
            }}
          >
            {TARGET_COLLECTION_OPTIONS.map((option) => (
              <option key={option} value={option}>
                {TARGET_COLLECTION_LABEL[option]}
              </option>
            ))}
          </select>
        </div>

        <div className="flex flex-col gap-2">
          <label htmlFor="rag-doc-publisher" className={ADMIN_FORM_LABEL_CLASS}>
            발행 기관 (선택)
          </label>
          <input
            id="rag-doc-publisher"
            type="text"
            className={ADMIN_FORM_INPUT_CLASS}
            placeholder="예: 국토교통부"
            value={publisher}
            onChange={(event) => setPublisher(event.target.value)}
          />
        </div>

        <div className="flex flex-col gap-2">
          <label htmlFor="rag-doc-effective-date" className={ADMIN_FORM_LABEL_CLASS}>
            시행일 (선택, 법규만)
          </label>
          <input
            id="rag-doc-effective-date"
            type="date"
            className={ADMIN_FORM_INPUT_CLASS}
            value={effectiveDate}
            onChange={(event) => setEffectiveDate(event.target.value)}
          />
        </div>

        <div className="flex flex-col gap-2">
          <label htmlFor="rag-doc-authored-at" className={ADMIN_FORM_LABEL_CLASS}>
            작성일 (선택, 하자 지식만)
          </label>
          <input
            id="rag-doc-authored-at"
            type="date"
            className={ADMIN_FORM_INPUT_CLASS}
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
      </>
      )}
    </form>
  );
}
