import { useState } from 'react';
import { DefectPhoto, type DefectPhotoGroup } from './DefectPhoto';

interface PhotosSectionPreviewProps {
  /** 사진 단위로 묶인 하자 그룹 — 같은 사진의 하자 여러 건이 한 장에 함께 표시된다(#1333). */
  photoGroups: DefectPhotoGroup[];
}

function PhotoPlaceholder() {
  return (
    <div className="flex aspect-video items-center justify-center rounded-md bg-surface-sunken text-xs text-text-muted">
      이미지 없음
    </div>
  );
}

const PAGE_SIZE = 9;

// "부위별 사진"은 확정 하자 이미지에서 자동 파생되는 데이터라 편집할 필드가 없다(제출문·참여
// 기술진 명단과 달리 수동 입력 대상이 아님). 그래도 다른 섹션과 함께 자유롭게 순서를 바꿀 수
// 있어야 하므로 고정 섹션 카드로 두되, 이 카드 자체는 미리보기 전용이다 — 실제 PDF 삽입은
// exportReportToPdf의 부위별 사진 표가 담당한다(박스도 그쪽에서 함께 그린다).
export function PhotosSectionPreview({ photoGroups }: PhotosSectionPreviewProps) {
  const [page, setPage] = useState(0);
  const validGroups = photoGroups.filter((group) => Boolean(group.imageUrl));
  const defectCount = validGroups.reduce((sum, group) => sum + group.defects.length, 0);
  const totalPages = Math.max(1, Math.ceil(validGroups.length / PAGE_SIZE));
  const safePage = Math.min(Math.max(0, page), totalPages - 1);
  const pageGroups = validGroups.slice(safePage * PAGE_SIZE, safePage * PAGE_SIZE + PAGE_SIZE);

  return (
    <section className="flex flex-col gap-4">
      {validGroups.length === 0 ? (
        <div className="rounded-lg border border-border bg-surface-muted p-8 text-center text-sm text-text-muted">
          점검 촬영 축소본이 없습니다.
        </div>
      ) : (
        <>
          <p className="text-xs text-text-muted">
            확정 하자의 촬영 축소본이 PDF의 "부위별 사진" 섹션에 자동 포함됩니다({validGroups.length}장 · 하자{' '}
            {defectCount}건).
          </p>
          {validGroups.length > PAGE_SIZE && (
            <div className="flex items-center justify-end">
              <div className="ml-1 flex items-center gap-1">
                <button
                  type="button"
                  onClick={() => setPage((current) => Math.max(0, current - 1))}
                  disabled={safePage === 0}
                  aria-label="부위별 사진 이전 페이지"
                  className="flex size-8 cursor-pointer items-center justify-center rounded-full border border-zinc-200 text-zinc-700 transition hover:bg-zinc-100 disabled:opacity-35"
                >
                  <svg
                    className="size-3.5"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2.5"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  >
                    <path d="m15 18-6-6 6-6" />
                  </svg>
                </button>
                <div className="flex items-center gap-1 px-2 text-xs" aria-live="polite">
                  <span className="font-bold text-zinc-900">{safePage + 1}</span>
                  <span className="font-normal text-zinc-500">/ {totalPages}</span>
                </div>
                <button
                  type="button"
                  onClick={() => setPage((current) => Math.min(totalPages - 1, current + 1))}
                  disabled={safePage === totalPages - 1}
                  aria-label="부위별 사진 다음 페이지"
                  className="flex size-8 cursor-pointer items-center justify-center rounded-full border border-zinc-200 text-zinc-700 transition hover:bg-zinc-100 disabled:opacity-35"
                >
                  <svg
                    className="size-3.5"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2.5"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  >
                    <path d="m9 18 6-6-6-6" />
                  </svg>
                </button>
              </div>
            </div>
          )}
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
            {pageGroups.map((group, index) => (
              <div
                key={group.mediaId ?? `defect-${group.defects[0]?.id ?? index}`}
                className="flex flex-col gap-1"
              >
                <DefectPhoto
                  group={group}
                  alt="점검 촬영 축소본"
                  imageClassName="w-full rounded-md"
                  fallback={<PhotoPlaceholder />}
                />
                {group.defects.length > 0 && (
                  <span className="text-[11px] text-text-muted">하자 {group.defects.length}건</span>
                )}
              </div>
            ))}
          </div>
        </>
      )}
    </section>
  );
}
