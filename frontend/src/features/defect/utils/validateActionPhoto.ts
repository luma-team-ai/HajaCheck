import { DEFECT_ACTION_PHOTO_ALLOWED_TYPES, DEFECT_ACTION_PHOTO_MAX_SIZE_BYTES } from '../constants';

export type ActionPhotoError = 'FILE_INVALID_TYPE' | 'FILE_TOO_LARGE';

// "조치 후 사진 업로드" 드래그앤드롭 위젯 클라이언트측 검증(JPG/PNG, 최대 10MB — contract.md 필드 표).
export function validateActionPhoto(file: File): ActionPhotoError | null {
  if (!DEFECT_ACTION_PHOTO_ALLOWED_TYPES.includes(file.type)) return 'FILE_INVALID_TYPE';
  if (file.size > DEFECT_ACTION_PHOTO_MAX_SIZE_BYTES) return 'FILE_TOO_LARGE';
  return null;
}
