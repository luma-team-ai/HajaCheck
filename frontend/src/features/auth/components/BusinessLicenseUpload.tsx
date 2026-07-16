import { useRef } from 'react';
import { ERROR_CLASSES, LABEL_CLASSES } from '../formClasses';
import { formatFileSize } from '../utils/validateBusinessLicenseFile';
import ocrSuccessPreview from '../../../assets/brand/signup-ocr-success.svg';

interface BusinessLicenseUploadProps {
  file: File | null;
  onFileSelect: (file: File | null) => void;
  errorMessage?: string | null;
}

// 사업자등록증 파일 업로드 — 파일명·용량 표시 + 삭제(✕) 지원. OCR은 stub이라 수동입력 필드 병행 안내
// ⚠️ OCR 자동인식 미구현(AI서버 business-license-ocr stub, 프록시 경로 부재, #292 handoff 근거) —
//    안내 문구·예상화면 미리보기는 시안이 자동채움을 보여줘도 그대로 유지한다(구현 금지).
export function BusinessLicenseUpload({ file, onFileSelect, errorMessage }: BusinessLicenseUploadProps) {
  const inputRef = useRef<HTMLInputElement>(null);

  const handleChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    onFileSelect(event.target.files?.[0] ?? null);
  };

  const handleRemove = () => {
    onFileSelect(null);
    if (inputRef.current) inputRef.current.value = '';
  };

  return (
    <div className="flex flex-col gap-1.5">
      <label className={LABEL_CLASSES} htmlFor="business-registration-file">
        사업자등록증
      </label>
      <input
        ref={inputRef}
        id="business-registration-file"
        type="file"
        accept="image/jpeg,image/png,application/pdf"
        className="text-sm text-text-default"
        onChange={handleChange}
      />

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

      {errorMessage && <p className={ERROR_CLASSES}>{errorMessage}</p>}

      <p className="m-0 text-xs text-text-muted">
        사업자등록번호·상호명·대표자명 자동인식은 준비 중입니다. 아래 항목을 직접 입력해 주세요.
      </p>

      <div className="flex flex-col items-start gap-1.5">
        <img
          src={ocrSuccessPreview}
          className="max-w-full rounded-lg border border-dashed border-border"
          alt=""
          aria-hidden="true"
        />
        <span className="text-xs text-text-muted">자동인식 완료 시 예상 화면 (준비 중)</span>
      </div>
    </div>
  );
}
