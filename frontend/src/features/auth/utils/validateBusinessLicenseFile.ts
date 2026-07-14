// 사업자등록증 업로드 클라이언트측 검증 — 계약(contract.md) FILE_REQUIRED/FILE_INVALID_TYPE/FILE_TOO_LARGE와 코드 정합
import { BUSINESS_LICENSE_ALLOWED_TYPES, BUSINESS_LICENSE_MAX_SIZE_BYTES } from '../constants';

export type BusinessLicenseFileError = 'FILE_REQUIRED' | 'FILE_INVALID_TYPE' | 'FILE_TOO_LARGE';

export function validateBusinessLicenseFile(file: File | null): BusinessLicenseFileError | null {
  if (!file) return 'FILE_REQUIRED';
  if (!BUSINESS_LICENSE_ALLOWED_TYPES.includes(file.type)) return 'FILE_INVALID_TYPE';
  if (file.size > BUSINESS_LICENSE_MAX_SIZE_BYTES) return 'FILE_TOO_LARGE';
  return null;
}

// 업로드 위젯에 표시할 용량 문자열(예: "1.2MB", "512KB")
export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
}
