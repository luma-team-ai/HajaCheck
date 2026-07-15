import { useRef } from 'react';
import { formatFileSize } from '../utils/validateBusinessLicenseFile';

interface BusinessLicenseUploadProps {
  file: File | null;
  onFileSelect: (file: File | null) => void;
  errorMessage?: string | null;
}

// 사업자등록증 파일 업로드 — 파일명·용량 표시 + 삭제(✕) 지원. OCR은 stub이라 수동입력 필드 병행 안내
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
    <div className="auth-form-field">
      <label className="auth-form-label" htmlFor="business-registration-file">
        사업자등록증
      </label>
      <input
        ref={inputRef}
        id="business-registration-file"
        type="file"
        accept="image/jpeg,image/png,application/pdf"
        className="auth-file-input"
        onChange={handleChange}
      />

      {file && (
        <div className="auth-file-selected">
          <span className="auth-file-selected-name">{file.name}</span>
          <span className="auth-file-selected-size">{formatFileSize(file.size)}</span>
          <button
            type="button"
            className="auth-file-remove-btn"
            aria-label="첨부 파일 삭제"
            onClick={handleRemove}
          >
            ✕
          </button>
        </div>
      )}

      {errorMessage && <p className="auth-form-error">{errorMessage}</p>}

      <p className="auth-form-hint">사업자등록번호·상호명·대표자명 자동인식은 준비 중입니다. 아래 항목을 직접 입력해 주세요.</p>
    </div>
  );
}
