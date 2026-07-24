import { useRef, useState, type ChangeEvent, type DragEvent, type FormEvent } from 'react';
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
// /api/defects/{id}/status를 상태전이(RESOLVED)+조치결과 필드로 확장하는 것으로 가정한다(BE 판단
// 대기 — contract.md §엔드포인트 매핑 ③조치 결과 등록, [CONTRACT-CHANGE-REQUEST] 후보).
export function DefectActionForm({ defectId, inspectionId, actionResult, onSubmitted }: Props) {
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [actionContent, setActionContent] = useState('');
  const [actionDate, setActionDate] = useState('');
  const [assigneeId, setAssigneeId] = useState<number | ''>('');
  const [isDragActive, setIsDragActive] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const { data: assignableUsers, isLoading: isAssigneeLoading } = useDefectAssignableUsers();
  const { uploadActionPhoto, isPending: isUploading } = useUploadDefectActionPhoto();
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
      await submitAction({
        status: 'RESOLVED',
        actionContent: actionContent.trim(),
        actionDate,
        assigneeId,
        afterMediaId: uploaded[0]?.id,
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
          role="button"
          tabIndex={0}
        >
          {file ? <span>{file.name}</span> : <span>클릭하거나 파일을 끌어다 놓으세요 (JPG, PNG, 최대 10MB)</span>}
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
