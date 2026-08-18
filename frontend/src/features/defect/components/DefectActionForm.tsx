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
import { REASON_REQUIRED_TARGETS } from '../constants/defectStatusWorkflow';
import { useChangeDefectStatus } from '../hooks/useChangeDefectStatus';
import { useDefectAssignableUsers } from '../hooks/useDefectAssignableUsers';
import { useSubmitDefectAction } from '../hooks/useSubmitDefectAction';
import { useUploadDefectActionPhoto } from '../hooks/useUploadDefectActionPhoto';
import { validateActionPhoto } from '../utils/validateActionPhoto';
import { DEFECT_STATUS_LABEL } from '../types';
import type { Defect, DefectActionResult, DefectStatus } from '../types';
import { DefectStatusReasonModal } from './DefectStatusReasonModal';

type Props = {
  defect: Defect;
  actionResult: DefectActionResult | null | undefined;
  // 같은 사진(mediaId) 그룹의 하자 건수(#1644) — DefectDetailModal이 defectGroupSummary.ts로 미리
  // 계산해 넘긴다. 1(단독 하자, 기본값)이면 그룹 안내를 표시하지 않는다 — 기존 호출부·테스트가
  // 넘기지 않아도(옵셔널) 회귀 없이 단독 하자 취급되도록 기본값을 둔다.
  groupSize?: number;
  onSubmitted?: () => void;
};

const PHOTO_ERROR_MESSAGE: Record<'FILE_INVALID_TYPE' | 'FILE_TOO_LARGE', string> = {
  FILE_INVALID_TYPE: '허용되지 않는 파일 형식입니다. (JPG, PNG만 가능)',
  FILE_TOO_LARGE: '파일 용량이 너무 큽니다. (최대 10MB)',
};

// "진행상태" select(#1128, #1193/HAJA-569로 확장) — CONFIRMED에서는 백엔드가 정방향 1단계 전이만
// 허용하므로(Defect#changeStatus) 유효한 값이 IN_PROGRESS 하나뿐이라 여전히 고정 select다. 반면
// IN_PROGRESS에서는 백엔드가 "같은 상태 유지 재제출"을 허용하도록 완화됐다(조치중 사진을 시간차를
// 두고 여러 번 등록하기 위함, 이 select를 원래 만든 의도) — 그래서 IN_PROGRESS일 때는 두 값
// (IN_PROGRESS=유지/RESOLVED=완료) 중 사용자가 실제로 고른다.
const ACTION_STATUS_OPTIONS: Partial<Record<DefectStatus, ReadonlyArray<'IN_PROGRESS' | 'RESOLVED'>>> = {
  CONFIRMED: ['IN_PROGRESS'],
  IN_PROGRESS: ['IN_PROGRESS', 'RESOLVED'],
};

const ACTION_STATUS_LABEL: Record<'IN_PROGRESS' | 'RESOLVED', string> = {
  IN_PROGRESS: '조치중',
  RESOLVED: '조치완료',
};

// "다른 상태로 변경"(DefectStatusChangeControl, HAJA-349/#630) 통합(#1556) — 역행/건너뛰기 대상은
// 조치 내용·사진 없이 사유만으로 전이하므로, 이 select에서 골라도 아래 조치 필드는 건드리지 않고
// 곧바로 사유 입력 모달을 띄운다. 실제 제출은 useChangeDefectStatus(PATCH /status)가 담당한다.
type StatusSelectOption = { value: DefectStatus; mode: 'forward' | 'reason' };

function buildStatusOptions(status: DefectStatus): StatusSelectOption[] {
  const forward = (ACTION_STATUS_OPTIONS[status] ?? []).map(
    (value): StatusSelectOption => ({ value, mode: 'forward' }),
  );
  const reason = (REASON_REQUIRED_TARGETS[status] ?? []).map(
    (value): StatusSelectOption => ({ value, mode: 'reason' }),
  );
  return [...forward, ...reason];
}

function todayDateString(): string {
  const today = new Date();
  const year = today.getFullYear();
  const month = String(today.getMonth() + 1).padStart(2, '0');
  const day = String(today.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

// 하자 상세 모달 "상태 저장" 폼(#1128) — contract.md §"조치 결과 등록" 필드 표 확정: 조치 후 사진
// (필수, 드래그앤드롭), 조치 내용(필수), 조치일(필수), 담당자(필수) + 진행상태(select, 필수). 제출 시
// PATCH /api/defects/{id}/action(DefectActionResultRequest)을 호출하며, targetStatus로 IN_PROGRESS
// (조치중)/RESOLVED(조치완료) 중 실제 전이할 상태를 명시한다 — 과거엔 항상 RESOLVED 고정이었으나
// 이제 CONFIRMED→IN_PROGRESS 등록도 이 폼으로 한다.
export function DefectActionForm({ defect, actionResult, groupSize = 1, onSubmitted }: Props) {
  const { id: defectId, inspectionId, status } = defect;
  const statusOptions = ACTION_STATUS_OPTIONS[status];
  const statusSelectOptions = buildStatusOptions(status);
  const maxActionDate = todayDateString();
  // 보수적 기본값(#1128 코드리뷰 P2-2 취지 계승) — IN_PROGRESS처럼 옵션이 2개면 "완료"가 아니라
  // "유지(IN_PROGRESS)"를 기본 선택해, select를 건드리지 않고 실수로 조치완료까지 가는 걸 막는다.
  const [targetStatus, setTargetStatus] = useState<'IN_PROGRESS' | 'RESOLVED'>(
    statusOptions?.[0] ?? 'IN_PROGRESS',
  );
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
  // 이미지 단위 보수 작업 그룹 팬아웃(v0.2, #1456/#1457) — 백엔드가 groupSize>1로 응답하면 같은
  // 이미지의 다른 하자들도 함께 갱신됐다는 뜻이라, 사용자가 "왜 다른 카드도 같이 바뀌었지" 하고
  // 당황하지 않도록 성공 문구에 덧붙인다. groupSize<=1(단독 하자)이면 기존 문구 그대로 둔다.
  const [justSavedGroupSize, setJustSavedGroupSize] = useState<number | null>(null);
  // "다른 상태로 변경" 통합(#1556) — 진행상태 select에서 역행/건너뛰기 대상을 고르면 조치 필드는
  // 그대로 둔 채 사유 입력 모달만 띄운다. null이면 select는 정방향 값(targetStatus)을 보여준다.
  const [pendingReasonTarget, setPendingReasonTarget] = useState<DefectStatus | null>(null);
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
  const {
    changeStatus,
    isPending: isChangingStatus,
    error: changeStatusError,
    resetError: resetChangeStatusError,
  } = useChangeDefectStatus(defectId, inspectionId);

  async function handleReasonSubmit(reason: string) {
    if (!pendingReasonTarget) return;
    try {
      await changeStatus({ status: pendingReasonTarget, reason });
      setPendingReasonTarget(null);
    } catch {
      // 실패 메시지는 아래 인라인 alert로 노출 — 모달은 열어 둔 채 재시도할 수 있게 한다.
    }
  }

  function handleCancelReason() {
    resetChangeStatusError();
    setPendingReasonTarget(null);
  }

  const reasonModal = pendingReasonTarget && (
    <DefectStatusReasonModal
      defect={defect}
      targetStatus={pendingReasonTarget}
      onCancel={handleCancelReason}
      onSubmit={handleReasonSubmit}
      isSubmitting={isChangingStatus}
      submitError={changeStatusError ? '상태 변경에 실패했습니다. 잠시 후 다시 시도해 주세요.' : null}
    />
  );

  // RESOLVED(조치완료)는 정방향 전이가 더 없으므로, 등록된 조치 결과를 읽기 전용 요약으로 보여주고
  // 조치 등록 폼은 닫는다(재등록 방지). 다만 "다른 상태로 변경" 통합(#1556)으로 사유와 함께
  // IN_PROGRESS로 되돌리는 것은 여기서도 진행상태 select로 계속 제공한다. CONFIRMED→IN_PROGRESS
  // 단계에서도 actionResult가 채워지지만(같은 등록 필드를 공유), IN_PROGRESS는 아직 RESOLVED로
  // 한 번 더 전이해야 하므로 이 시점엔 폼을 계속 보여준다(#1128).
  //
  // actionResult 유무와 무관하게 이 분기를 탄다(#1610) — 검수확정에서 조치완료로 "건너뛰기"
  // 전이(updateStatus, 사유만 입력)한 하자는 조치 등록 폼을 거친 적이 없어 actionContent가 비어
  // actionResult가 null이다. 과거엔 actionResult가 있어야만 이 분기를 타서, 그 경우 다음 분기
  // (statusOptions == null → RESOLVED는 미정의)에 걸려 폼 전체가 사라지고 되돌리기 select도 함께
  // 사라졌다 — 백엔드는 RESOLVED→IN_PROGRESS 역행을 허용하는데 UI 진입로만 없던 상태.
  // (#1642) CONFIRMED→RESOLVED 직행은 이제 "진행상태" select에서 제거돼 새로 발생하지 않지만,
  // 이 변경 전에 그 경로로 생성된 기존 레코드는 여전히 actionResult가 null인 채 RESOLVED일 수 있어
  // 이 방어 분기는 계속 필요하다.
  if (status === 'RESOLVED') {
    return (
      <section className="defect-action-form defect-action-form--registered" aria-label="조치 결과">
        <h2>조치 결과 등록</h2>
        {actionResult ? (
          <>
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
          </>
        ) : (
          <p className="defect-action-form__empty-summary">등록된 조치 내용이 없습니다.</p>
        )}

        <div className="defect-action-form__field">
          <label htmlFor="defect-action-target-status">진행상태 *</label>
          <select
            id="defect-action-target-status"
            value={pendingReasonTarget ?? status}
            disabled={isChangingStatus}
            onChange={(event) => {
              const value = event.target.value as DefectStatus;
              setPendingReasonTarget(value === status ? null : value);
            }}
          >
            <option value={status}>{DEFECT_STATUS_LABEL[status]}</option>
            {statusSelectOptions.map((option) => (
              <option key={option.value} value={option.value}>
                {DEFECT_STATUS_LABEL[option.value]}(으)로 되돌리기
              </option>
            ))}
          </select>
        </div>

        {reasonModal}
      </section>
    );
  }

  // DETECTED(신규, 검수 전)는 조치 등록 대상이 될 수 없다 — 정상 플로우에선 카드그리드에서 이미
  // 숨겨지지만(#1128과 별개 PR), 방어적으로 이 패널 자체를 렌더링하지 않는다.
  if (statusOptions == null) {
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
    setJustSavedGroupSize(null);
  }

  function handleFileInputChange(event: ChangeEvent<HTMLInputElement>) {
    const candidate = event.target.files?.[0];
    if (candidate) {
      applyFile(candidate);
    }
  }

  function handleActionDateChange(event: ChangeEvent<HTMLInputElement>) {
    const nextActionDate = event.target.value;
    if (nextActionDate && nextActionDate > maxActionDate) {
      event.currentTarget.value = actionDate;
      return;
    }
    setActionDate(nextActionDate);
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
    actionDate <= maxActionDate &&
    assigneeId !== '' &&
    !isUploading &&
    !isSubmitting;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // statusOptions는 컴포넌트 최상단의 이른 반환으로 이미 null이 아님이 보장되지만, TS는 중첩 함수
    // 경계를 넘어 그 좁히기를 유지하지 않는다 — 여기서 다시 한번 방어적으로 확인한다.
    if (!canSubmit || file == null || typeof assigneeId !== 'number' || statusOptions == null) return;

    try {
      const uploaded = await uploadActionPhoto({ inspectionId, file });
      const uploadedMediaId = uploaded[0]?.id;
      if (uploadedMediaId == null) {
        throw new Error('조치 후 사진 업로드 결과가 없습니다.');
      }
      const updated = await submitAction({
        actionContent: actionContent.trim(),
        actionDate,
        actionAssigneeId: assigneeId,
        actionMediaId: uploadedMediaId,
        targetStatus,
      });
      // 저장 성공 후 필드를 초기화한다(#1128 코드리뷰 P2-2) — 초기화하지 않으면 폼이 그대로 채워진
      // 채 남아 재클릭 시 같은 사진이 중복 업로드되고 사유 없이 다음 단계까지 넘어갈 수 있다.
      setJustSavedLabel(ACTION_STATUS_LABEL[targetStatus]);
      setJustSavedGroupSize(updated.groupSize != null && updated.groupSize > 1 ? updated.groupSize : null);
      setFile(null);
      setActionContent('');
      setActionDate('');
      setAssigneeId('');
      // IN_PROGRESS 유지 제출 직후엔 select를 다시 안전한 기본값(유지)으로 되돌린다 — RESOLVED로
      // 전이됐다면 이 컴포넌트는 다음 렌더에서 읽기 전용 요약 분기로 대체되므로 이 리셋은 무해하다.
      setTargetStatus(statusOptions[0]);
      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }
      onSubmitted?.();
    } catch {
      // 에러 메시지는 submitError/업로드 훅 error를 통해 아래에서 노출한다 — 여기서는 흐름만 중단.
    }
  }

  // 폼이 뭘 더 채워야 활성화되는지 안 보여서 "상태 저장" 버튼이 그냥 고장난 것처럼 보인다는
  // 지적(#1436) — 버튼 스타일 자체(공용 Button, 53개 화면 공유)는 건드리지 않는다. 처음엔 버튼
  // 아래 문장 하나로 부족 항목을 나열했는데, 디자인 리뷰에서 "그 문장이 실제 빈 필드와 공간적으로
  // 안 이어져 있어 다시 훑어야 한다"는 지적을 받아 — 각 라벨 옆에 직접 "필수" 표시를 붙이는 방식으로
  // 바꿨다(값이 채워지면 즉시 사라짐).
  const isPhotoMissing = file == null;
  const isContentMissing = actionContent.trim().length === 0;
  const isDateMissing = actionDate.trim().length === 0;
  const isAssigneeMissing = assigneeId === '';

  return (
    <form className="defect-action-form" aria-label="조치 결과 등록" onSubmit={handleSubmit}>
      <h2>조치 결과 등록</h2>

      {/* 등록 전 그룹 사전 안내(#1644) — 과거엔 제출 후(justSavedGroupSize)에만 그룹 반영 사실을
          알려줘 "왜 다른 카드도 같이 바뀌었지" 하고 당황하는 문제가 있었다. 같은 사진의 하자가
          여럿이면 등록 시작 전부터 미리 알린다. */}
      {groupSize > 1 && (
        <p className="defect-action-form__group-notice" role="note">
          같은 사진의 하자 {groupSize}건에 함께 반영됩니다.
        </p>
      )}

      <div className="defect-action-form__section">
        <p className="defect-action-form__section-label">사진</p>
        <div className="defect-action-form__field">
          <span className="defect-action-form__label-row">
            <label htmlFor="defect-action-photo">조치 후 사진 업로드</label>
            {isPhotoMissing && <span className="defect-action-form__required-flag" aria-hidden="true">필수</span>}
          </span>
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
              tabIndex={-1}
            />
          </div>
          {fileError && (
            <p className="defect-action-form__error" role="alert">
              {fileError}
            </p>
          )}
        </div>
      </div>

      <div className="defect-action-form__section">
        <p className="defect-action-form__section-label">조치 세부정보</p>

        <div className="defect-action-form__field">
          <span className="defect-action-form__label-row">
            <label htmlFor="defect-action-content">조치 내용</label>
            {isContentMissing && <span className="defect-action-form__required-flag" aria-hidden="true">필수</span>}
          </span>
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
            <span className="defect-action-form__label-row">
              <label htmlFor="defect-action-date">조치일</label>
              {isDateMissing && <span className="defect-action-form__required-flag" aria-hidden="true">필수</span>}
            </span>
            <input
              id="defect-action-date"
              type="date"
              value={actionDate}
              max={maxActionDate}
              onChange={handleActionDateChange}
            />
          </div>

          <div className="defect-action-form__field">
            <label htmlFor="defect-action-target-status">진행상태 *</label>
            <select
              id="defect-action-target-status"
              value={pendingReasonTarget ?? targetStatus}
              disabled={statusSelectOptions.length < 2}
              onChange={(event) => {
                const value = event.target.value as DefectStatus;
                const option = statusSelectOptions.find((candidate) => candidate.value === value);
                if (!option) return;
                if (option.mode === 'forward') {
                  setPendingReasonTarget(null);
                  setTargetStatus(value as 'IN_PROGRESS' | 'RESOLVED');
                } else {
                  setPendingReasonTarget(value);
                }
              }}
            >
              {statusSelectOptions.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.mode === 'forward'
                    ? ACTION_STATUS_LABEL[option.value as 'IN_PROGRESS' | 'RESOLVED']
                    : `${DEFECT_STATUS_LABEL[option.value]}(으)로 되돌리기`}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="defect-action-form__field">
          <span className="defect-action-form__label-row">
            <label htmlFor="defect-action-assignee">담당자</label>
            {isAssigneeMissing && <span className="defect-action-form__required-flag" aria-hidden="true">필수</span>}
          </span>
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

      {justSavedLabel && (
        <p className="defect-action-form__success" role="status">
          {justSavedLabel}(으)로 저장되었습니다.
          {justSavedGroupSize != null &&
            ` (같은 이미지의 하자 ${justSavedGroupSize}건에 함께 반영됨)`}
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

      {reasonModal}
    </form>
  );
}
