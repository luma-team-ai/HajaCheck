// 촬영 데이터 업로드 클라이언트측 검증 — 백엔드 MediaUploadProperties(허용 타입/용량/개수 상한)와 정합.
import {
  MEDIA_ALLOWED_TYPES,
  MEDIA_ALLOWED_VIDEO_TYPES,
  MEDIA_MAX_FILES_PER_REQUEST,
  MEDIA_MAX_SIZE_BYTES,
  MEDIA_VIDEO_MAX_SIZE_BYTES,
} from '../constants';

export type MediaFileError = 'FILE_INVALID_TYPE' | 'FILE_TOO_LARGE';
export type MediaKind = 'image' | 'video';

// 파일 단위 오류 — 선택 즉시 어떤 파일이 왜 걸렸는지 보여주기 위해 파일별로 반환한다.
// 이미지(JPG/PNG) 전용 — 실제 POST /api/inspections/{id}/media 대상이 되는 파일만 여기로 검증한다.
export function validateMediaFile(file: File): MediaFileError | null {
  if (!MEDIA_ALLOWED_TYPES.includes(file.type)) return 'FILE_INVALID_TYPE';
  if (file.size > MEDIA_MAX_SIZE_BYTES) return 'FILE_TOO_LARGE';
  return null;
}

// 이미지/영상/미지원으로 분류 — 영상은 업로드 대상이 아니라 용량만 확인한다(classifyMediaFile 참고).
export function classifyMediaFile(file: File): MediaKind | null {
  if (MEDIA_ALLOWED_TYPES.includes(file.type)) return 'image';
  if (MEDIA_ALLOWED_VIDEO_TYPES.includes(file.type)) return 'video';
  return null;
}

// 영상 용량 검증 — 타입은 classifyMediaFile에서 이미 확인되므로 용량만 본다.
export function validateVideoFile(file: File): MediaFileError | null {
  if (file.size > MEDIA_VIDEO_MAX_SIZE_BYTES) return 'FILE_TOO_LARGE';
  return null;
}

// 개수 상한(요청 단위) — 이미 선택된 파일 + 새로 추가하려는 파일의 합으로 판단.
export function exceedsMaxFileCount(currentCount: number, addingCount: number): boolean {
  return currentCount + addingCount > MEDIA_MAX_FILES_PER_REQUEST;
}

// 업로드 위젯에 표시할 용량 문자열(예: "1.2MB", "512KB") — feature 간 직접 import 금지(React_코드_컨벤션.md
// §1)라 auth/utils/validateBusinessLicenseFile.ts의 동일 함수를 재사용하지 않고 로컬로 복제한다.
export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
}
