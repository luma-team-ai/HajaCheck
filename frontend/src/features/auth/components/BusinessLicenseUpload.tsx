import { useRef, useState } from 'react';
import { ERROR_CLASSES, LABEL_CLASSES } from '../formClasses';
import {
  formatFileSize,
  validateBusinessLicenseFile,
  type BusinessLicenseFileError,
} from '../utils/validateBusinessLicenseFile';

interface BusinessLicenseUploadProps {
  file: File | null;
  onFileSelect: (file: File | null) => void;
  errorMessage?: string | null;
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
// OCR 자동인식은 AI서버 stub이라 미구현(#292 handoff 근거) — 안내 문구만 유지, 구현 금지.
// (#298: 바이트 동일한 랜딩 히어로 이미지가 "자동인식 예상화면"으로 잘못 노출되던 블록 제거)
export function BusinessLicenseUpload({ file, onFileSelect, errorMessage }: BusinessLicenseUploadProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [isDragActive, setIsDragActive] = useState(false);
  // accept 속성은 드래그앤드롭엔 적용되지 않아(#298) 클릭 선택과 달리 아무 파일이나 들어온다 —
  // 드롭 직후 즉시 로컬 검증해 위반 시 onFileSelect를 호출하지 않고 여기서 바로 에러를 보여준다.
  const [dropError, setDropError] = useState<BusinessLicenseFileError | null>(null);

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
          <span aria-hidden="true">📄</span>
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

      <p className="m-0 text-xs text-text-muted">
        사업자등록번호·상호명·대표자명 자동인식은 준비 중입니다. 아래 항목을 직접 입력해 주세요.
      </p>
    </div>
  );
}
