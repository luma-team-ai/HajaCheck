import { useRef, useState, type ChangeEvent } from 'react';
import type { LocationDrawingPhotoItem, LocationDrawingPhotosSectionData } from '../../types';
import { resizeImageToDataUrl } from '../../utils/resizeImageToDataUrl';

interface LocationDrawingPhotosFormProps {
  data: LocationDrawingPhotosSectionData;
  onChange: (next: LocationDrawingPhotosSectionData) => void;
  readOnly: boolean;
}

// 위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도(#1409) — 원본 서식에서 이 섹션은 텍스트가 아니라 이미지
// 자체가 본문이다. 백엔드 업로드 API가 없어(신설 시 PR머신 파일업로드 보안 리뷰 트리거) 이미지를
// content_json에 base64로 직접 저장한다 — FacilityPhotoUploadField와 달리 서버 업로드가 없으므로
// objectURL이 아니라 최종 data URL을 그대로 미리보기 src로 쓴다(별도 revoke 불필요).
export function LocationDrawingPhotosForm({ data, onChange, readOnly }: LocationDrawingPhotosFormProps) {
  const images = data.images;
  const inputRef = useRef<HTMLInputElement>(null);
  const [isProcessing, setIsProcessing] = useState(false);

  const addFiles = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    const imageFiles = Array.from(files).filter((file) => file.type.startsWith('image/'));
    if (imageFiles.length === 0) return;

    setIsProcessing(true);
    try {
      const resized = await Promise.all(imageFiles.map((file) => resizeImageToDataUrl(file)));
      const nextItems: LocationDrawingPhotoItem[] = resized.map((dataUrl) => ({ dataUrl, caption: '' }));
      onChange({ images: [...images, ...nextItems] });
    } finally {
      setIsProcessing(false);
    }
  };

  const handleInputChange = (event: ChangeEvent<HTMLInputElement>) => {
    void addFiles(event.target.files);
    event.target.value = '';
  };

  const updateCaption = (index: number, caption: string) => {
    onChange({ images: images.map((image, imageIndex) => (imageIndex === index ? { ...image, caption } : image)) });
  };

  const removeImage = (index: number) => {
    onChange({ images: images.filter((_, imageIndex) => imageIndex !== index) });
  };

  return (
    <section className="flex flex-col gap-4">
      <p className="text-sm text-text-muted">이미지와 캡션을 추가하면 PDF에 사진+캡션 형식으로 반영됩니다.</p>

      {!readOnly && (
        <div>
          <button
            type="button"
            onClick={() => inputRef.current?.click()}
            disabled={isProcessing}
            className="rounded-full border border-dashed border-border px-4 py-2 text-sm font-medium text-text-default transition hover:bg-surface-muted disabled:cursor-not-allowed disabled:opacity-60"
          >
            {isProcessing ? '이미지 처리 중...' : '+ 이미지 추가'}
          </button>
          <input
            ref={inputRef}
            type="file"
            accept="image/*"
            multiple
            className="hidden"
            onChange={handleInputChange}
            aria-label="위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도 이미지 업로드"
          />
        </div>
      )}

      {images.length === 0 ? (
        <div className="rounded-lg border border-border bg-surface-muted p-8 text-center text-sm text-text-muted">
          추가된 이미지가 없습니다.
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          {images.map((image, index) => (
            <div key={index} className="flex flex-col gap-3 rounded-lg border border-border p-4 sm:flex-row sm:items-start">
              <img
                src={image.dataUrl}
                alt={image.caption || `이미지 ${index + 1}`}
                className="h-32 w-full shrink-0 rounded-lg border border-border object-cover sm:w-48"
              />
              <div className="flex flex-1 flex-col gap-1.5">
                <label className="flex flex-col gap-1.5">
                  <span className="text-xs font-medium tracking-wide text-text-muted">캡션</span>
                  <input
                    className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm leading-6 text-text-default outline-none transition placeholder:text-text-muted focus:border-primary focus:ring-2 focus:ring-primary/10 read-only:cursor-default read-only:bg-surface-muted read-only:text-text-muted"
                    value={image.caption}
                    readOnly={readOnly}
                    placeholder="예: 한남대교 위치도"
                    onChange={(event) => updateCaption(index, event.target.value)}
                  />
                </label>
                {!readOnly && (
                  <button
                    type="button"
                    aria-label={`이미지 ${index + 1}번 삭제`}
                    onClick={() => removeImage(index)}
                    className="self-start rounded-full border border-border px-3 py-1.5 text-xs text-text-muted transition hover:border-red-200 hover:text-red-600"
                  >
                    삭제
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
