import { useEffect, useRef, useState } from 'react';
import {
  ERROR_CLASSES,
  LABEL_CLASSES,
  OCR_FEEDBACK_NEUTRAL_CLASSES,
  OCR_FEEDBACK_WARNING_CLASSES,
  SUCCESS_CLASSES,
} from '../formClasses';
import {
  formatFileSize,
  validateBusinessLicenseFile,
  type BusinessLicenseFileError,
} from '../utils/validateBusinessLicenseFile';
import { BusinessLicenseImageLightbox } from './BusinessLicenseImageLightbox';

// OCR 결과 피드백(#748) — 상태 판단(stale 가드·실제 채운 필드 수)은 CompanySignupPage 소유,
// 이 컴포넌트는 결과만 그대로 표시한다(단방향 계층 유지).
export type OcrFeedbackStatus = 'success' | 'empty' | 'error';

export interface OcrFeedbackState {
  status: OcrFeedbackStatus;
  filledCount: number;
}

const OCR_FEEDBACK_CLASSNAME: Record<OcrFeedbackStatus, string> = {
  success: SUCCESS_CLASSES,
  empty: OCR_FEEDBACK_NEUTRAL_CLASSES,
  error: OCR_FEEDBACK_WARNING_CLASSES,
};

function ocrFeedbackText(feedback: OcrFeedbackState): string {
  if (feedback.status === 'success') return `✓ ${feedback.filledCount}개 항목이 자동입력됐어요`;
  if (feedback.status === 'empty') return '인식된 정보가 없어요. 아래 항목을 직접 입력해 주세요';
  return '자동인식에 실패했어요. 아래 항목을 직접 입력해 주세요';
}

interface BusinessLicenseUploadProps {
  file: File | null;
  onFileSelect: (file: File | null) => void;
  errorMessage?: string | null;
  // 사업자등록증 OCR 자동채움(#587) — 호출부(CompanySignupPage)가 useBusinessLicenseOcr().isPending을
  // 그대로 전달. 컴포넌트는 API를 모르고 로딩 문구 표시만 담당(단방향 계층 유지).
  isOcrLoading?: boolean;
  // OCR 결과 피드백(#748) — 성공/인식값 0개/실패를 CompanySignupPage가 판단해 내려준다.
  ocrFeedback?: OcrFeedbackState | null;
}

// 드롭 경로 전용 에러 메시지 — CompanySignupPage의 ERROR_MESSAGES(제출시 검증)와 텍스트는 같지만
// 그쪽은 페이지 내부 로컬 상수라 컴포넌트가 import할 수 없다(계층 역참조 금지, formClasses.ts만 공유).
// 드롭은 제출 전 즉시 피드백이 목적이라 이 컴포넌트 안에 최소 복제한다 — 단, 허용 타입/용량 판정 자체는
// validateBusinessLicenseFile + constants.ts(BUSINESS_LICENSE_ALLOWED_TYPES 등) 단일 소스를 그대로 재사용한다.
const DROP_ERROR_MESSAGES: Record<BusinessLicenseFileError, string> = {
  FILE_REQUIRED: '사업자등록증 파일을 첨부해 주세요.',
  FILE_INVALID_TYPE: '지원하지 않는 파일 형식입니다. (JPG, PNG, PDF만 가능)',
  FILE_TOO_LARGE: '파일 용량이 너무 큽니다. (최대 10MB)',
};

// 사업자등록증 파일 업로드 — 드래그앤드롭 + "파일 선택" 버튼, 파일명·용량 표시 + 삭제(✕) 지원.
// OCR 자동채움 구현(#587) — jpeg/png 업로드 시 사업자정보 3필드를 자동채움(호출·상태관리는
// CompanySignupPage 소유, 이 컴포넌트는 로딩 문구만 표시). PDF는 백엔드 OCR 미지원이라 자동인식
// 대상에서 제외된다(호출부에서 판정) — 이 컴포넌트는 안내 문구로만 반영.
// (#298: 바이트 동일한 랜딩 히어로 이미지가 "자동인식 예상화면"으로 잘못 노출되던 블록 제거)
export function BusinessLicenseUpload({
  file,
  onFileSelect,
  errorMessage,
  isOcrLoading,
  ocrFeedback,
}: BusinessLicenseUploadProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [isDragActive, setIsDragActive] = useState(false);
  // accept 속성은 드래그앤드롭엔 적용되지 않아(#298) 클릭 선택과 달리 아무 파일이나 들어온다 —
  // 드롭 직후 즉시 로컬 검증해 위반 시 onFileSelect를 호출하지 않고 여기서 바로 에러를 보여준다.
  const [dropError, setDropError] = useState<BusinessLicenseFileError | null>(null);
  // 업로드 이미지 썸네일 미리보기(#748) — file.type이 image/*일 때만 objectURL을 만든다.
  // PDF 등은 미리보기 대상이 아니라 null 유지(기존 📄 아이콘 표시). 브라우저가 GC로 자동
  // 해제하지 않는 리소스라 파일 변경/언마운트 시 명시적으로 revoke한다(FacilityPhotoUploadField와
  // 동일 패턴).
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  // 썸네일 클릭 확대 라이트박스(#767) — previewUrl을 그대로 재사용(추가 objectURL 생성 없음).
  const [isLightboxOpen, setIsLightboxOpen] = useState(false);
  const thumbnailButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!file || !file.type.startsWith('image/')) {
      setPreviewUrl(null);
      return;
    }
    const url = URL.createObjectURL(file);
    setPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  // 파일이 삭제되거나 PDF로 교체돼 미리보기가 사라지면 열려 있던 라이트박스도 함께 닫는다
  // (stale 이미지 노출 방지).
  useEffect(() => {
    if (!previewUrl) {
      setIsLightboxOpen(false);
    }
  }, [previewUrl]);

  const closeLightbox = () => {
    setIsLightboxOpen(false);
    thumbnailButtonRef.current?.focus();
  };

  const acceptDroppedFile = (candidate: File | null) => {
    if (!candidate) return;
    const error = validateBusinessLicenseFile(candidate);
    if (error) {
      setDropError(error);
      return;
    }
    setDropError(null);
    onFileSelect(candidate);
  };

  const handleChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    setDropError(null);
    onFileSelect(event.target.files?.[0] ?? null);
  };

  const handleRemove = () => {
    setDropError(null);
    onFileSelect(null);
    if (inputRef.current) inputRef.current.value = '';
  };

  const handleDragOver = (event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setIsDragActive(true);
  };

  const handleDragLeave = () => {
    setIsDragActive(false);
  };

  const handleDrop = (event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setIsDragActive(false);
    acceptDroppedFile(event.dataTransfer.files?.[0] ?? null);
  };

  const displayErrorMessage = dropError ? DROP_ERROR_MESSAGES[dropError] : errorMessage;

  return (
    <div className="flex flex-col gap-1.5">
      <label className={LABEL_CLASSES} htmlFor="business-registration-file">
        사업자등록증
      </label>

      <div
        className={`flex flex-col items-center gap-2 rounded-xl border-2 border-dashed px-4 py-6 text-center transition-colors ${
          isDragActive ? 'border-primary bg-surface-muted' : 'border-border'
        }`}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
      >
        <span aria-hidden="true" className="text-2xl">
          📁
        </span>
        <p className="m-0 text-sm text-text-muted">파일을 끌어다 놓거나</p>
        <button
          type="button"
          className="cursor-pointer rounded-lg border border-border bg-surface px-4 py-2 text-sm font-semibold text-text-default hover:bg-surface-muted"
          onClick={() => inputRef.current?.click()}
        >
          파일 선택
        </button>
        <input
          ref={inputRef}
          id="business-registration-file"
          type="file"
          accept="image/jpeg,image/png,application/pdf"
          className="hidden"
          onChange={handleChange}
        />
        <p className="m-0 text-xs text-text-muted">PDF, JPG, PNG · 최대 10MB</p>
      </div>

      {file && (
        <div className="flex items-center gap-2 rounded-lg bg-surface-muted px-3 py-2 text-sm">
          {previewUrl ? (
            <button
              ref={thumbnailButtonRef}
              type="button"
              className="shrink-0 cursor-zoom-in rounded-md border-none bg-transparent p-0"
              aria-label="사업자등록증 이미지 크게 보기"
              onClick={() => setIsLightboxOpen(true)}
            >
              <img src={previewUrl} alt="" className="h-12 w-12 rounded-md object-cover" />
            </button>
          ) : (
            <span aria-hidden="true">📄</span>
          )}
          <span className="flex-1 truncate">{file.name}</span>
          <span className="text-text-muted">{formatFileSize(file.size)}</span>
          <button
            type="button"
            className="cursor-pointer border-none bg-transparent px-1 text-text-muted"
            aria-label="첨부 파일 삭제"
            onClick={handleRemove}
          >
            ✕
          </button>
        </div>
      )}

      {displayErrorMessage && <p className={ERROR_CLASSES}>{displayErrorMessage}</p>}

      {isOcrLoading && (
        <div
          className="flex items-center gap-2 rounded-lg border border-primary/20 bg-primary/5 px-3 py-2"
          role="status"
        >
          <span
            aria-hidden="true"
            className="h-4 w-4 shrink-0 animate-spin rounded-full border-2 border-primary/25 border-t-primary"
          />
          <span className="text-sm font-medium text-text-default">
            사업자등록증 정보를 자동인식하는 중입니다...
          </span>
        </div>
      )}

      {!isOcrLoading && ocrFeedback && (
        <p className={OCR_FEEDBACK_CLASSNAME[ocrFeedback.status]}>
          {ocrFeedbackText(ocrFeedback)}
        </p>
      )}

      <p className="m-0 text-xs text-text-muted">
        JPG, PNG 파일은 업로드 시 사업자등록번호·상호명·대표자명·개업일자가 자동으로
        채워집니다(자동채움 후에도 직접 수정 가능). PDF는 자동인식을 지원하지 않아 아래 항목을
        직접 입력해 주세요.
      </p>

      {isLightboxOpen && previewUrl && (
        <BusinessLicenseImageLightbox previewUrl={previewUrl} onClose={closeLightbox} />
      )}
    </div>
  );
}
