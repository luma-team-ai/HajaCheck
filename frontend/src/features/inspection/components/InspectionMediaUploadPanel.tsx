import { useRef, useState } from 'react';
import type { ChangeEvent, DragEvent } from 'react';
import type { MediaFileError, MediaKind } from '../utils/validateMediaFiles';
import { formatFileSize } from '../utils/validateMediaFiles';

export interface StagedMediaFile {
  file: File;
  kind: MediaKind;
  error: MediaFileError | null;
}

const ERROR_MESSAGES: Record<MediaFileError, string> = {
  FILE_INVALID_TYPE: '지원하지 않는 형식입니다 (JPG, PNG, MP4만 가능)',
  FILE_TOO_LARGE: '파일 용량이 너무 큽니다',
};

interface InspectionMediaUploadPanelProps {
  files: StagedMediaFile[];
  onFilesAdd: (files: File[]) => void;
  onRemove: (index: number) => void;
  /** 업로드 요청이 성공적으로 끝났는지 — true면 이미지 항목을 "업로드 완료"로 표시한다 */
  uploaded: boolean;
  /** 업로드 요청 진행 중의 전체 진행률(0~100) — null이면 대기 중 */
  uploadProgress: number | null;
  disabled?: boolean;
}

// 새 점검 생성 화면의 데이터 업로드 패널(회의 후 반영된 시안) — 이미지/영상 선택은 함께 받지만,
// 실제 POST /api/inspections/{id}/media는 이미지만 대상이다(MediaFileType.java: "영상은 후속 PR
// 범위"). 영상은 선택만 되고 "프레임 추출 예정" 상태로만 표시된다. 서버는 다건 이미지를 한 번에
// 업로드하는 단일 요청만 지원해 파일별 개별 진행률은 없다 — 요청 전체 진행률을 이미지 항목에
// 공통으로 보여준다(시안의 파일별 개별 %는 현재 백엔드 계약상 재현 불가).
export function InspectionMediaUploadPanel({
  files,
  onFilesAdd,
  onRemove,
  uploaded,
  uploadProgress,
  disabled = false,
}: InspectionMediaUploadPanelProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [isDragActive, setIsDragActive] = useState(false);

  const handleChange = (event: ChangeEvent<HTMLInputElement>) => {
    const picked = Array.from(event.target.files ?? []);
    if (picked.length > 0) onFilesAdd(picked);
    if (inputRef.current) inputRef.current.value = '';
  };

  const handleDragOver = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    if (!disabled) setIsDragActive(true);
  };

  const handleDragLeave = () => setIsDragActive(false);

  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setIsDragActive(false);
    if (disabled) return;
    const dropped = Array.from(event.dataTransfer.files ?? []);
    if (dropped.length > 0) onFilesAdd(dropped);
  };

  const totalSize = files.reduce((sum, entry) => sum + entry.file.size, 0);

  return (
    <div className="flex flex-col gap-4">
      <div
        className={`flex flex-col items-center gap-2 rounded-2xl border-2 border-dashed px-4 py-10 text-center transition-colors ${
          isDragActive ? 'border-primary bg-surface-muted' : 'border-neutral-300'
        }`}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
      >
        <span
          aria-hidden="true"
          className="flex size-16 items-center justify-center rounded-full bg-white text-2xl shadow-sm"
        >
          📁
        </span>
        <p className="m-0 text-base font-medium text-zinc-900">이미지 또는 영상을 끌어다 놓으세요</p>
        <p className="m-0 text-sm text-neutral-600">
          JPG·PNG·MP4 · 영상은 프레임 자동 추출 · 최대 500MB
        </p>
        <button
          type="button"
          className="cursor-pointer rounded-full border border-neutral-300 bg-white px-6 py-2 text-sm font-medium text-zinc-900 hover:bg-surface-muted disabled:cursor-not-allowed disabled:opacity-50"
          onClick={() => inputRef.current?.click()}
          disabled={disabled}
        >
          파일 선택
        </button>
        <input
          ref={inputRef}
          aria-label="촬영 데이터 파일 선택"
          type="file"
          accept="image/jpeg,image/png,video/mp4"
          multiple
          className="hidden"
          onChange={handleChange}
          disabled={disabled}
        />
      </div>

      {files.length > 0 && (
        <div className="flex flex-col rounded-2xl border border-neutral-300/20 bg-white shadow-sm">
          {files.map((entry, index) => (
            <MediaFileRow
              key={`${entry.file.name}-${index}`}
              entry={entry}
              uploaded={uploaded}
              uploadProgress={uploadProgress}
              onRemove={() => onRemove(index)}
              disabled={disabled}
              isLast={index === files.length - 1}
            />
          ))}
        </div>
      )}

      {files.length > 0 && (
        <p className="m-0 text-sm text-neutral-600">
          총 {files.length}개 파일 · {formatFileSize(totalSize)}
        </p>
      )}
    </div>
  );
}

function MediaFileRow({
  entry,
  uploaded,
  uploadProgress,
  onRemove,
  disabled,
  isLast,
}: {
  entry: StagedMediaFile;
  uploaded: boolean;
  uploadProgress: number | null;
  onRemove: () => void;
  disabled: boolean;
  isLast: boolean;
}) {
  const { file, kind, error } = entry;

  return (
    <div className={`flex items-center gap-4 p-3 ${isLast ? '' : 'border-b border-neutral-300/10'}`}>
      <div
        className="flex size-12 shrink-0 items-center justify-center rounded-xl bg-[#E6E0E9] text-xl"
        aria-hidden="true"
      >
        {kind === 'video' ? '🎞️' : '🖼️'}
      </div>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="flex items-center gap-2">
          <span className="truncate text-base font-semibold text-zinc-900">{file.name}</span>
          <span className="shrink-0 text-xs text-neutral-600">({formatFileSize(file.size)})</span>
        </div>
        {error ? (
          <span className="text-xs text-danger">{ERROR_MESSAGES[error]}</span>
        ) : kind === 'video' ? (
          <span className="w-fit rounded-full bg-[#E6E0E9] px-2 py-0.5 text-[10px] font-medium text-[#494551]">
            영상 · 프레임 추출 예정
          </span>
        ) : uploaded ? (
          <span className="text-[11px] font-medium text-emerald-600">✓ 업로드 완료</span>
        ) : uploadProgress !== null ? (
          <div className="flex items-center gap-2">
            <div className="h-1 w-32 overflow-hidden rounded-full bg-[#E6E0E9]">
              <div
                className="h-full rounded-full bg-zinc-900 transition-all duration-300"
                style={{ width: `${uploadProgress}%` }}
              />
            </div>
            <span className="text-xs font-semibold text-zinc-900">{uploadProgress}%</span>
          </div>
        ) : (
          <span className="text-xs text-neutral-500">대기 중</span>
        )}
      </div>
      <button
        type="button"
        className="shrink-0 cursor-pointer rounded-full border-none bg-transparent px-1 text-neutral-500 disabled:cursor-not-allowed disabled:opacity-50"
        aria-label={`${file.name} 삭제`}
        onClick={onRemove}
        disabled={disabled}
      >
        ✕
      </button>
    </div>
  );
}
