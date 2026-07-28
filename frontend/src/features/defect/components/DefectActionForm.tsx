import {
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
  type DragEvent,
  type FormEvent,
  type KeyboardEvent as ReactKeyboardEvent,
  type MouseEvent as ReactMouseEvent,
} from 'react';
import { Button } from '../../../shared/components/Button';
import { useDefectAssignableUsers } from '../hooks/useDefectAssignableUsers';
import { useSubmitDefectAction } from '../hooks/useSubmitDefectAction';
import { useUploadDefectActionPhoto } from '../hooks/useUploadDefectActionPhoto';
import { validateActionPhoto } from '../utils/validateActionPhoto';
import type { DefectActionResult, DefectStatus } from '../types';

type Props = {
  defectId: number;
  inspectionId: number;
  status: DefectStatus;
  actionResult: DefectActionResult | null | undefined;
  onSubmitted?: () => void;
};

const PHOTO_ERROR_MESSAGE: Record<'FILE_INVALID_TYPE' | 'FILE_TOO_LARGE', string> = {
  FILE_INVALID_TYPE: '허용되지 않는 파일 형식입니다. (JPG, PNG만 가능)',
  FILE_TOO_LARGE: '파일 용량이 너무 큽니다. (최대 10MB)',
};

// "진행상태" select(#1128) — 백엔드가 정방향 1단계 전이만 허용하므로(Defect#changeStatus), 현재
// 상태에서 유효한 다음 단계는 항상 하나뿐이다. 자유 2지선다가 아니라 이 맵으로 도출한 단일 값만
// select에 노출한다(실질적으로 값이 고정된 select — 자유 선택 UI가 아님).
const NEXT_ACTION_STATUS: Partial<Record<DefectStatus, 'IN_PROGRESS' | 'RESOLVED'>> = {
  CONFIRMED: 'IN_PROGRESS',
  IN_PROGRESS: 'RESOLVED',
};

const ACTION_STATUS_LABEL: Record<'IN_PROGRESS' | 'RESOLVED', string> = {
  IN_PROGRESS: '조치중',
  RESOLVED: '조치완료',
};

// 하자 상세 모달 "상태 저장" 폼(#1128) — contract.md §"조치 결과 등록" 필드 표 확정: 조치 후 사진
// (필수, 드래그앤드롭), 조치 내용(필수), 조치일(필수), 담당자(필수) + 진행상태(select, 필수). 제출 시
// PATCH /api/defects/{id}/action(DefectActionResultRequest)을 호출하며, targetStatus로 IN_PROGRESS
// (조치중)/RESOLVED(조치완료) 중 실제 전이할 상태를 명시한다 — 과거엔 항상 RESOLVED 고정이었으나
// 이제 CONFIRMED→IN_PROGRESS 등록도 이 폼으로 한다.
export function DefectActionForm({ defectId, inspectionId, status, actionResult, onSubmitted }: Props) {
  const nextStatus = NEXT_ACTION_STATUS[status];
  const [file, setFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [actionContent, setActionContent] = useState('');
  const [actionDate, setActionDate] = useState('');
  const [assigneeId, setAssigneeId] = useState<number | ''>('');
  const [isDragActive, setIsDragActive] = useState(false);
  // 제출 성공 알림(#1128 코드리뷰 P2-2) — IN_PROGRESS 등록 후에도 같은 폼이 계속 보이므로, 성공
  // 피드백 없이 필드가 그대로 남아있으면 사용자가 재클릭해 같은 사진을 중복 업로드하거나 의도치
  // 않게 다음 단계(조치완료)까지 가버릴 수 있다. 새 파일을 선택하면(재등록 시작) 지운다.
  const [justSavedLabel, setJustSavedLabel] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // 업로드 드롭존 썸네일 미리보기(#969) — BusinessLicenseUpload.tsx:84-92와 동일한 단일 파일용
  // objectURL 생성/해제 패턴(이 컴포넌트도 파일을 항상 1개만 다룬다).
  useEffect(() => {
    if (!file) {
      setPreviewUrl(null);
      return;
    }
    const url = URL.createObjectURL(file);
    setPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  const { data: assignableUsers, isLoading: isAssigneeLoading } = useDefectAssignableUsers();
  const { uploadActionPhoto, isPending: isUploading, error: uploadError } = useUploadDefectActionPhoto();
  const { submitAction, isPending: isSubmitting, error: submitError } = useSubmitDefectAction(
    defectId,
    inspectionId,
  );

  // RESOLVED(조치완료)는 종료 상태라 더 이상 전이가 없으므로, 등록된 조치 결과를 읽기 전용
  // 요약으로 보여주고 폼을 닫는다(재등록 방지). CONFIRMED→IN_PROGRESS 단계에서도 actionResult가
  // 채워지지만(같은 등록 필드를 공유), IN_PROGRESS는 아직 RESOLVED로 한 번 더 전이해야 하므로
  // 이 시점엔 폼을 계속 보여준다(#1128).
  if (actionResult && status === 'RESOLVED') {
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

  // DETECTED(신규, 검수 전)는 조치 등록 대상이 될 수 없다 — 정상 플로우에선 카드그리드에서 이미
  // 숨겨지지만(#1128과 별개 PR), 방어적으로 이 패널 자체를 렌더링하지 않는다.
  if (nextStatus == null) {
    return null;
  }

  function applyFile(candidate: File) {
    const error = validateActionPhoto(candidate);
    if (error) {
      setFileError(PHOTO_ERROR_MESSAGE[error]);
      return;
    }
    setFileError(null);
    setFile(candidate);
    setJustSavedLabel(null);
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

  // 미리보기 제거(✕) 버튼 — 드롭존 자체의 onClick(파일선택창 재오픈)이 함께 발화하지 않도록
  // stopPropagation, 그리고 같은 파일을 다시 선택해도 onChange가 재발화하도록 input value를 비운다.
  function handleRemoveFile(event: ReactMouseEvent<HTMLButtonElement>) {
    event.stopPropagation();
    setFile(null);
    setFileError(null);
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
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
    // nextStatus는 컴포넌트 최상단의 이른 반환(94-96행)으로 이미 null이 아님이 보장되지만, TS는
    // 중첩 함수 경계를 넘어 그 좁히기를 유지하지 않는다 — 여기서 다시 한번 방어적으로 확인한다.
    if (!canSubmit || file == null || typeof assigneeId !== 'number' || nextStatus == null) return;

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
        targetStatus: nextStatus,
      });
      // 저장 성공 후 필드를 초기화한다(#1128 코드리뷰 P2-2) — 초기화하지 않으면 폼이 그대로 채워진
      // 채 남아 재클릭 시 같은 사진이 중복 업로드되고 사유 없이 다음 단계까지 넘어갈 수 있다.
      setJustSavedLabel(ACTION_STATUS_LABEL[nextStatus]);
      setFile(null);
      setActionContent('');
      setActionDate('');
      setAssigneeId('');
      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }
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
          className={`defect-action-form__dropzone${isDragActive ? ' is-drag-active' : ''}${file ? ' has-preview' : ''}`}
          onDrop={handleDrop}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onClick={() => fileInputRef.current?.click()}
          onKeyDown={handleDropzoneKeyDown}
          role="button"
          tabIndex={0}
        >
          {file ? (
            <>
              <img
                src={previewUrl ?? undefined}
                alt="조치 후 사진 미리보기"
                className="defect-action-form__preview-image"
              />
              <div className="defect-action-form__preview-chip">
                <span>{file.name}</span>
                <button
                  type="button"
                  className="defect-action-form__preview-remove"
                  aria-label="선택한 사진 제거"
                  onClick={handleRemoveFile}
                >
                  ✕
                </button>
              </div>
            </>
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
          <label htmlFor="defect-action-target-status">진행상태 *</label>
          <select id="defect-action-target-status" value={nextStatus} disabled>
            <option value={nextStatus}>{ACTION_STATUS_LABEL[nextStatus]}</option>
          </select>
        </div>
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

      {justSavedLabel && (
        <p className="defect-action-form__success" role="status">
          {justSavedLabel}(으)로 저장되었습니다.
        </p>
      )}

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
        {isUploading || isSubmitting ? '저장하는 중...' : '상태 저장'}
      </Button>
    </form>
  );
}
