import { useState } from 'react';

interface PhotosSectionPreviewProps {
  imageUrls: Array<string | null | undefined>;
}

function PhotoThumbnail({ src }: { src?: string | null }) {
  const [failed, setFailed] = useState(false);

  if (!src || failed) {
    return (
      <div className="flex aspect-video items-center justify-center rounded-md bg-surface-sunken text-xs text-text-muted">
        이미지 없음
      </div>
    );
  }

  return (
    <img
      src={src}
      alt="점검 촬영 축소본"
      className="aspect-video w-full rounded-md object-cover"
      onError={() => setFailed(true)}
    />
  );
}

// "부위별 사진"은 확정 하자 이미지에서 자동 파생되는 데이터라 편집할 필드가 없다(제출문·참여
// 기술진 명단과 달리 수동 입력 대상이 아님). 그래도 다른 섹션과 함께 자유롭게 순서를 바꿀 수
// 있어야 하므로 고정 섹션 카드로 두되, 이 카드 자체는 미리보기 전용이다 — 실제 PDF 삽입은
// exportReportToPdf의 부위별 사진 표가 담당한다.
export function PhotosSectionPreview({ imageUrls }: PhotosSectionPreviewProps) {
  const validUrls = imageUrls.filter((url): url is string => Boolean(url));

  return (
    <section className="flex flex-col gap-4">
      {validUrls.length === 0 ? (
        <div className="rounded-lg border border-border bg-surface-muted p-8 text-center text-sm text-text-muted">
          점검 촬영 축소본이 없습니다.
        </div>
      ) : (
        <>
          <p className="text-xs text-text-muted">
            확정 하자의 촬영 축소본이 PDF의 "부위별 사진" 섹션에 자동 포함됩니다({validUrls.length}장).
          </p>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
            {validUrls.map((url, index) => (
              <PhotoThumbnail key={`${url}-${index}`} src={url} />
            ))}
          </div>
        </>
      )}
    </section>
  );
}
