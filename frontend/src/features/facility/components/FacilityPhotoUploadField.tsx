import type { ChangeEvent, DragEvent } from 'react';
import { useEffect, useRef, useState } from 'react';
import { LABEL_CLASSES } from '../formClasses';

const MAX_PHOTO_COUNT = 4;

interface StagedPhoto {
  id: string;
  file: File;
  previewUrl: string;
}

// 등록 모달 "대표 사진(최대 4장)" — UI만 구성한다(#629 범위). 실제 멀티파트 업로드 연동은
// 대표 사진 백엔드 계약(facility_photos 테이블, #632)이 아직 없어 #652 대기 중 — 선택한 파일은
// 로컬 미리보기(objectURL)까지만 처리하고, 저장 요청(CreateFacilityRequest)에는 포함하지 않는다.
// 실 업로드 연동 시 이 컴포넌트가 onFilesChange로 선택 파일 목록을 상위에 노출하도록 확장한다.
export function FacilityPhotoUploadField() {
  const [photos, setPhotos] = useState<StagedPhoto[]>([]);
  const [isDraggingOver, setIsDraggingOver] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  // 언마운트 cleanup이 항상 최신 photos를 참조하도록 ref로 미러링한다 — dep []인 채로 photos를
  // 그대로 클로저에 캡처하면 마운트 시점의(빈) 배열만 정리되어, 사진 추가 후 모달이 닫혀
  // 언마운트될 때 실제 생성된 blob URL이 revoke되지 않는 누수가 있었다(PR머신 react-reviewer P2).
  const photosRef = useRef(photos);
  photosRef.current = photos;

  // 언마운트 시 objectURL 누수 방지 — 브라우저가 GC로 자동 해제하지 않는 리소스라 명시적으로 해제한다.
  useEffect(() => {
    return () => {
      photosRef.current.forEach((photo) => URL.revokeObjectURL(photo.previewUrl));
    };
  }, []);

  const addFiles = (files: FileList | null) => {
    if (!files || files.length === 0) return;

    setPhotos((prev) => {
      const remainingSlots = MAX_PHOTO_COUNT - prev.length;
      if (remainingSlots <= 0) return prev;

      const nextPhotos = Array.from(files)
        .slice(0, remainingSlots)
        .filter((file) => file.type.startsWith('image/'))
        .map((file) => ({
          id: `${file.name}-${file.size}-${file.lastModified}`,
          file,
          previewUrl: URL.createObjectURL(file),
        }));

      return [...prev, ...nextPhotos];
    });
  };

  const handleInputChange = (event: ChangeEvent<HTMLInputElement>) => {
    addFiles(event.target.files);
    event.target.value = '';
  };

  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setIsDraggingOver(false);
    addFiles(event.dataTransfer.files);
  };

  const handleRemove = (id: string) => {
    setPhotos((prev) => {
      const target = prev.find((photo) => photo.id === id);
      if (target) {
        URL.revokeObjectURL(target.previewUrl);
      }
      return prev.filter((photo) => photo.id !== id);
    });
  };

  const isFull = photos.length >= MAX_PHOTO_COUNT;

  return (
    <div className="flex flex-col gap-1">
      <span className={LABEL_CLASSES}>대표 사진 (최대 {MAX_PHOTO_COUNT}장)</span>
      <div
        role="button"
        tabIndex={isFull ? -1 : 0}
        aria-disabled={isFull}
        onClick={() => !isFull && inputRef.current?.click()}
        onKeyDown={(event) => {
          if (!isFull && (event.key === 'Enter' || event.key === ' ')) {
            event.preventDefault();
            inputRef.current?.click();
          }
        }}
        onDragOver={(event) => {
          event.preventDefault();
          if (!isFull) setIsDraggingOver(true);
        }}
        onDragLeave={() => setIsDraggingOver(false)}
        onDrop={isFull ? undefined : handleDrop}
        className={`flex flex-col items-center justify-center gap-1 rounded-lg border-2 border-dashed px-4 py-6 text-center text-sm transition ${
          isFull
            ? 'cursor-not-allowed border-border bg-surface-muted text-text-subtle'
            : isDraggingOver
              ? 'cursor-pointer border-primary bg-surface-muted text-text-default'
              : 'cursor-pointer border-border bg-surface-muted text-text-muted hover:border-primary'
        }`}
      >
        <span aria-hidden="true" className="text-xl">
          📷
        </span>
        <span>
          {isFull
            ? `최대 ${MAX_PHOTO_COUNT}장까지 선택했습니다.`
            : '클릭하거나 파일을 끌어다 놓아 사진을 추가하세요'}
        </span>
        <input
          ref={inputRef}
          type="file"
          accept="image/*"
          multiple
          className="hidden"
          onChange={handleInputChange}
          disabled={isFull}
          aria-label="대표 사진 업로드"
        />
      </div>

      {photos.length > 0 && (
        <div className="mt-1 grid grid-cols-4 gap-2">
          {photos.map((photo) => (
            <div
              key={photo.id}
              className="group relative aspect-square overflow-hidden rounded-lg border border-border bg-surface-muted"
            >
              <img
                src={photo.previewUrl}
                alt={photo.file.name}
                className="h-full w-full object-cover"
              />
              <button
                type="button"
                aria-label={`${photo.file.name} 제거`}
                onClick={() => handleRemove(photo.id)}
                className="absolute right-1 top-1 rounded-full bg-black/60 px-1.5 py-0.5 text-xs leading-none text-white"
              >
                ✕
              </button>
            </div>
          ))}
        </div>
      )}

      <p className="m-0 text-xs text-text-muted">
        사진 업로드 연동은 준비 중입니다(#652) — 현재는 미리보기만 제공되며 등록 시 전송되지 않습니다.
      </p>
    </div>
  );
}
