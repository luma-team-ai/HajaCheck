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

// "부위별 사진"은 확정 하자 이미지에서 자동 파생되는 데이터라 편집할 필드가 없다(제출문·참여
// 기술진 명단과 달리 수동 입력 대상이 아님). 그래도 다른 섹션과 함께 자유롭게 순서를 바꿀 수
// 있어야 하므로 고정 섹션 카드로 두되, 이 카드 자체는 미리보기 전용이다 — 실제 PDF 삽입은
// exportReportToPdf의 부위별 사진 표가 담당한다(박스도 그쪽에서 함께 그린다).
export function PhotosSectionPreview({ photoGroups }: PhotosSectionPreviewProps) {
  const validGroups = photoGroups.filter((group) => Boolean(group.imageUrl));
  const defectCount = validGroups.reduce((sum, group) => sum + group.defects.length, 0);

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
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
            {validGroups.map((group, index) => (
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
