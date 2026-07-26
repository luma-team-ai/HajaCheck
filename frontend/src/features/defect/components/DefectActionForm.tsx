import {
  useRef,
  useState,
  type ChangeEvent,
  type DragEvent,
  type FormEvent,
  type KeyboardEvent as ReactKeyboardEvent,
} from 'react';
import { Button } from '../../../shared/components/Button';
import { useDefectAssignableUsers } from '../hooks/useDefectAssignableUsers';
import { useSubmitDefectAction } from '../hooks/useSubmitDefectAction';
import { useUploadDefectActionPhoto } from '../hooks/useUploadDefectActionPhoto';
import { validateActionPhoto } from '../utils/validateActionPhoto';
import type { DefectActionResult } from '../types';

type Props = {
  defectId: number;
  inspectionId: number;
  actionResult: DefectActionResult | null | undefined;
  onSubmitted?: () => void;
};

const PHOTO_ERROR_MESSAGE: Record<'FILE_INVALID_TYPE' | 'FILE_TOO_LARGE', string> = {
  FILE_INVALID_TYPE: '허용되지 않는 파일 형식입니다. (JPG, PNG만 가능)',
  FILE_TOO_LARGE: '파일 용량이 너무 큽니다. (최대 10MB)',
};

// 하자 상세 모달 "조치 결과 등록" 폼 — contract.md §"조치 결과 등록" 필드 표 확정: 조치 후 사진
// (필수, 드래그앤드롭), 조치 내용(필수), 조치일(필수), 담당자(필수). 제출 시 PATCH
// /api/defects/{id}/action(DefectActionResultRequest)을 호출한다 — 상태 전이(RESOLVED)는 백엔드가
// 내부에서 항상 고정 처리하므로 요청 바디에 status를 싣지 않는다(contract.md §엔드포인트 매핑
// ③조치 결과 등록 확정).
export function DefectActionForm({ defectId, inspectionId, actionResult, onSubmitted }: Props) {
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [actionContent, setActionContent] = useState('');
  const [actionDate, setActionDate] = useState('');
  const [assigneeId, setAssigneeId] = useState<number | ''>('');
  const [isDragActive, setIsDragActive] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const { data: assignableUsers, isLoading: isAssigneeLoading } = useDefectAssignableUsers();
  const { uploadActionPhoto, isPending: isUploading, error: uploadError } = useUploadDefectActionPhoto();
  const { submitAction, isPending: isSubmitting, error: submitError } = useSubmitDefectAction(
    defectId,
    inspectionId,
  );

  // 이미 등록된 조치 결과가 있으면 폼 대신 읽기 전용 요약을 보여준다(재등록 방지).
  if (actionResult) {
    return (
      <section className="defect-action-form defect-action-form--registered" aria-label="조치 결과">
        <h2>조치 결과 등록</h2>
        <dl className="defect-action-form__summary">
          <dt>조치 내용</dt>
          <dd>{actionResult.actionContent}</dd>
          <dt>조치일</dt>
          <dd>{actionResult.actionDate}</dd>
          <dt>담당자</dt>
          <dd>{actionResult.assigneeName}</dd>
        </dl>
        {actionResult.afterPhotoUrl && (
          <img className="defect-action-form__after-photo" src={actionResult.afterPhotoUrl} alt="조치 후 사진" />
        )}
      </section>
    );
  }

  function applyFile(candidate: File) {
    const error = validateActionPhoto(candidate);
    if (error) {
      setFileError(PHOTO_ERROR_MESSAGE[error]);
      return;
    }
    setFileError(null);
    setFile(candidate);
  }

  function handleFileInputChange(event: ChangeEvent<HTMLInputElement>) {
    const candidate = event.target.files?.[0];
    if (candidate) {
      applyFile(candidate);
    }
  }

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    setIsDragActive(false);
    const candidate = event.dataTransfer.files?.[0];
    if (candidate) {
      applyFile(candidate);
    }
  }

  function handleDragOver(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    setIsDragActive(true);
  }

  function handleDragLeave() {
    setIsDragActive(false);
  }

  // 드롭존이 role="button"이라 클릭뿐 아니라 키보드(Enter/Space)로도 활성화돼야 한다(코드리뷰 P1).
  function handleDropzoneKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Enter' || event.key === ' ' || event.key === 'Spacebar') {
      event.preventDefault();
      fileInputRef.current?.click();
    }
  }

  const canSubmit =
    file != null &&
    actionContent.trim().length > 0 &&
    actionDate.trim().length > 0 &&
    assigneeId !== '' &&
    !isUploading &&
    !isSubmitting;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit || file == null || typeof assigneeId !== 'number') return;

    try {
      const uploaded = await uploadActionPhoto({ inspectionId, file });
      const uploadedMediaId = uploaded[0]?.id;
      if (uploadedMediaId == null) {
        throw new Error('조치 후 사진 업로드 결과가 없습니다.');
      }
      await submitAction({
        actionContent: actionContent.trim(),
        actionDate,
        actionAssigneeId: assigneeId,
        actionMediaId: uploadedMediaId,
      });
      onSubmitted?.();
    } catch {
      // 에러 메시지는 submitError/업로드 훅 error를 통해 아래에서 노출한다 — 여기서는 흐름만 중단.
    }
  }

  return (
    <form className="defect-action-form" aria-label="조치 결과 등록" onSubmit={handleSubmit}>
      <h2>조치 결과 등록</h2>

      <div className="defect-action-form__field">
        <label htmlFor="defect-action-photo">조치 후 사진 업로드 *</label>
        <div
          className={`defect-action-form__dropzone${isDragActive ? ' is-drag-active' : ''}`}
          onDrop={handleDrop}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onClick={() => fileInputRef.current?.click()}
          onKeyDown={handleDropzoneKeyDown}
          role="button"
          tabIndex={0}
        >
          {file ? (
            <span>{file.name}</span>
          ) : (
            <>
              <svg
                className="defect-action-form__dropzone-icon"
                viewBox="0 0 24 24"
                fill="none"
                aria-hidden="true"
              >
                <path
                  d="M7 16a4 4 0 0 1-.5-7.97A5 5 0 0 1 16.9 6.02 4.5 4.5 0 0 1 17.5 15H16m-8 3 4-4m0 0 4 4m-4-4v9"
                  stroke="currentColor"
                  strokeWidth="1.6"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
              <span className="defect-action-form__dropzone-text">
                <strong>파일을 드래그하거나 클릭하여 업로드</strong>
                <small>JPG, PNG 파일 (최대 10MB)</small>
              </span>
            </>
          )}
          <input
            id="defect-action-photo"
            ref={fileInputRef}
            type="file"
            accept="image/jpeg,image/png"
            onChange={handleFileInputChange}
            className="sr-only"
          />
        </div>
        {fileError && (
          <p className="defect-action-form__error" role="alert">
            {fileError}
          </p>
        )}
      </div>

      <div className="defect-action-form__field">
        <label htmlFor="defect-action-content">조치 내용 *</label>
        <textarea
          id="defect-action-content"
          placeholder="조치 내용을 입력해 주세요."
          value={actionContent}
          onChange={(event) => setActionContent(event.target.value)}
          rows={4}
        />
      </div>

      <div className="defect-action-form__row">
        <div className="defect-action-form__field">
          <label htmlFor="defect-action-date">조치일 *</label>
          <input
            id="defect-action-date"
            type="date"
            value={actionDate}
            onChange={(event) => setActionDate(event.target.value)}
          />
        </div>

        <div className="defect-action-form__field">
          <label htmlFor="defect-action-assignee">담당자 *</label>
          <select
            id="defect-action-assignee"
            value={assigneeId}
            disabled={isAssigneeLoading}
            onChange={(event) => setAssigneeId(event.target.value === '' ? '' : Number(event.target.value))}
          >
            <option value="">담당자를 선택하세요</option>
            {(assignableUsers ?? []).map((user) => (
              <option key={user.id} value={user.id}>
                {user.name}
              </option>
            ))}
          </select>
        </div>
      </div>

      {uploadError && (
        <p className="defect-action-form__error" role="alert">
          조치 후 사진 업로드에 실패했습니다. 잠시 후 다시 시도해 주세요.
        </p>
      )}

      {submitError && (
        <p className="defect-action-form__error" role="alert">
          조치 결과 등록에 실패했습니다. 잠시 후 다시 시도해 주세요.
        </p>
      )}

      <Button type="submit" variant="primary" size="lg" disabled={!canSubmit}>
        {isUploading || isSubmitting ? '등록하는 중...' : '조치 완료 등록'}
      </Button>
    </form>
  );
}
