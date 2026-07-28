import type { ChangeEvent, DragEvent } from 'react';
import { useEffect, useRef, useState } from 'react';
import { FACILITY_PHOTO_MAX_COUNT } from '../constants';
import { LABEL_CLASSES } from '../formClasses';

const MAX_PHOTO_COUNT = FACILITY_PHOTO_MAX_COUNT;

interface StagedPhoto {
  id: string;
  file: File;
  previewUrl: string;
}

type Props = {
  // 선택된 파일 배열이 바뀔 때마다(추가/삭제) 호출 — 상위(FacilityFormModal)가 실제 업로드 시점에
  // 쓸 File[]을 들고 있도록 노출한다(#652). 미리보기/objectURL 관리는 이 컴포넌트가 계속 전담하고,
  // 상위는 File 객체 배열만 받는다.
  onFilesChange?: (files: File[]) => void;
};

// 등록 모달 "대표 사진(최대 4장)" — UI(#629) + 실 업로드 연동(#652, POST /api/facilities/{id}/media).
// 미리보기/드래그드롭/개별삭제/objectURL cleanup은 이 컴포넌트가 계속 전담하고, 선택된 File 배열만
// onFilesChange로 상위에 노출한다 — 실제 전송(FormData 구성, 진행률)은 useUploadFacilityPhotos가 담당.
export function FacilityPhotoUploadField({ onFilesChange }: Props) {
  const [photos, setPhotos] = useState<StagedPhoto[]>([]);
  const [isDraggingOver, setIsDraggingOver] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  // 언마운트 cleanup이 항상 최신 photos를 참조하도록 ref로 미러링한다 — dep []인 채로 photos를
  // 그대로 클로저에 캡처하면 마운트 시점의(빈) 배열만 정리되어, 사진 추가 후 모달이 닫혀
  // 언마운트될 때 실제 생성된 blob URL이 revoke되지 않는 누수가 있었다(PR머신 react-reviewer P2).
  // ref 쓰기는 렌더 본문이 아니라 effect 안에서 한다("never write ref during render" — 재검수 P2).
  const photosRef = useRef(photos);
  useEffect(() => {
    photosRef.current = photos;
  }, [photos]);

  // 언마운트 시 objectURL 누수 방지 — 브라우저가 GC로 자동 해제하지 않는 리소스라 명시적으로 해제한다.
  useEffect(() => {
    return () => {
      photosRef.current.forEach((photo) => URL.revokeObjectURL(photo.previewUrl));
    };
  }, []);

  // photos가 바뀔 때마다(추가/삭제) 선택된 File 배열을 상위에 노출한다(#652). setPhotos 업데이터
  // 내부에서 직접 호출하지 않는 이유 — React가 개발 모드(StrictMode)에서 useState 업데이터 함수를
  // 두 번 호출해 순수성을 검증하는데, 그 안에서 onFilesChange 같은 부수효과를 호출하면 이중 호출될
  // 수 있다. photos state 자체를 단일 진실로 삼아 effect에서 파생시키는 편이 안전하다.
  useEffect(() => {
    onFilesChange?.(photos.map((photo) => photo.file));
  }, [photos, onFilesChange]);

  const addFiles = (files: FileList | null) => {
    if (!files || files.length === 0) return;

    setPhotos((prev) => {
      const remainingSlots = MAX_PHOTO_COUNT - prev.length;
      if (remainingSlots <= 0) return prev;

      // filter를 slice보다 먼저 적용한다(PR머신 P3) — accept="image/*"는 드래그앤드롭에는
      // 적용되지 않아 비이미지 파일이 앞쪽에 섞여 들어올 수 있는데, slice를 먼저 하면 그
      // 비이미지가 슬롯을 차지해 뒤쪽 유효 이미지가 조용히 잘려나간다.
      const nextPhotos = Array.from(files)
        .filter((file) => file.type.startsWith('image/'))
        .slice(0, remainingSlots)
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
    </div>
  );
}
