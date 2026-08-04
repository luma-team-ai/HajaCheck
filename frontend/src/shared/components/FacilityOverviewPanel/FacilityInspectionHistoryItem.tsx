import { ImageWithFallback } from '../ImageWithFallback';

export interface FacilityOverviewHistoryItem {
  id: number;
  roundNo: number;
  /** YYYY-MM-DD */
  inspectionDate: string;
  inspectorName: string;
  status: string;
  imageCount: number;
  defectGradeBreakdown: { grade: string; count: number }[];
  /** 이전 회차 대비 변화 메모 — 최신 회차에만 존재 */
  changeNote?: string;
  /** 썸네일 미리보기 외 나머지 이미지 수 — 최신 회차에만 존재 */
  additionalImageCount?: number;
  /** 미리보기 썸네일 URL(최대 2장) — 최신 회차에만 존재(#1549) */
  thumbnailUrls?: string[];
}

type Props = {
  item: FacilityOverviewHistoryItem;
  /** 최신 회차만 진하게 표시하고 썸네일/변화 메모/결과 링크까지 펼친다(Figma dev mode 마크업 기준) */
  expanded: boolean;
  /** "+N" 클릭 시 호출(#1549) — 넘기지 않으면 버튼이 비활성 상태로만 표시된다. */
  onViewMoreClick?: (inspectionId: number) => void;
  /** "결과 보기" 클릭(#1359 후속) — 넘기지 않으면 버튼이 비활성 처리된다 */
  onViewResult?: (item: FacilityOverviewHistoryItem) => void;
  /** "보고서" 클릭(#1359 후속) — 넘기지 않으면 버튼이 비활성 처리된다 */
  onViewReport?: (item: FacilityOverviewHistoryItem) => void;
};

function formatDefectSummary(item: FacilityOverviewHistoryItem): string {
  const total = item.defectGradeBreakdown.reduce((sum, entry) => sum + entry.count, 0);
  const breakdown = item.defectGradeBreakdown
    .map((entry) => `${entry.grade} ${entry.count}`)
    .join(' · ');
  return `이미지 ${item.imageCount}장 · 하자 ${total}건 (${breakdown})`;
}

// 시설물 상세/점검(회차) 생성 화면이 공유하는 점검 이력 타임라인 항목(shared — 두 feature가 동일 UI를 쓴다).
export function FacilityInspectionHistoryItem({
  item,
  expanded,
  onViewMoreClick,
  onViewResult,
  onViewReport,
}: Props) {
  return (
    <div className={`relative flex flex-col gap-4 ${expanded ? '' : 'opacity-60'}`}>
      <span
        aria-hidden="true"
        className={`absolute -left-8 top-1 rounded-full ${
          expanded
            ? 'size-4 bg-zinc-900 outline outline-2 outline-offset-0 outline-white'
            : 'size-2.5 border border-white bg-neutral-300'
        }`}
      />

      <div className="flex flex-wrap items-center gap-3">
        <span
          className={`font-medium text-zinc-900 ${expanded ? 'text-xl leading-7' : 'text-base leading-6'}`}
        >
          {item.roundNo}회차 점검
        </span>
        <span className="text-base leading-6 font-normal text-neutral-600">
          — {item.inspectionDate} · {item.inspectorName}
        </span>
        <span className="ml-auto inline-flex items-center gap-1.5 rounded-full bg-zinc-200/30 px-2 py-0.5 outline outline-1 outline-offset-[-1px] outline-neutral-300/30">
          {expanded && <span className="size-1.5 rounded-full bg-zinc-900" aria-hidden="true" />}
          <span
            className={`text-xs font-medium tracking-wide ${expanded ? 'text-zinc-900' : 'text-neutral-600'}`}
          >
            {item.status}
          </span>
        </span>
      </div>

      <p className={`m-0 text-base leading-6 ${expanded ? 'text-zinc-900' : 'text-neutral-600'}`}>
        {formatDefectSummary(item)}
      </p>

      {expanded && (
        <>
          {/* 사진 미리보기 2칸은 "이미지가 있는지"로만 게이팅한다 — "+N" 버튼 노출 여부
              (additionalImageCount, 미리보기 2장 초과분이 있을 때만 존재)와는 별개 조건이다.
              과거엔 이 둘을 하나로 묶어서, 이미지가 1~2장뿐인 회차는 사진이 있어도 행 자체가
              렌더링되지 않는 버그가 있었다(#1575, "서초 브릿지" 이미지 1장 사례로 발견). */}
          {item.imageCount > 0 && (
            <div className="flex items-center gap-3">
              {[0, 1].map((slotIndex) => {
                const url = item.thumbnailUrls?.[slotIndex];
                const placeholderClass =
                  'size-24 rounded-xl bg-neutral-100 outline outline-1 outline-offset-[-1px] outline-neutral-300/30';
                return url ? (
                  <ImageWithFallback
                    key={slotIndex}
                    src={url}
                    alt={`${item.roundNo}회차 점검 사진 ${slotIndex + 1}`}
                    className="size-24 rounded-xl object-cover outline outline-1 outline-offset-[-1px] outline-neutral-300/30"
                    fallback={<div className={placeholderClass} />}
                  />
                ) : (
                  <div key={slotIndex} className={placeholderClass} />
                );
              })}
              {item.additionalImageCount !== undefined && (
                <button
                  type="button"
                  onClick={() => onViewMoreClick?.(item.id)}
                  className="flex size-24 cursor-pointer items-center justify-center rounded-xl border-none bg-zinc-200/30 text-base font-medium text-neutral-600 outline outline-1 outline-offset-[-1px] outline-neutral-300/30"
                >
                  +{item.additionalImageCount}
                </button>
              )}
            </div>
          )}

          {item.changeNote && (
            <div className="flex items-center gap-2 rounded-lg bg-neutral-50 p-4 outline outline-1 outline-offset-[-1px] outline-neutral-300/20">
              <span className="text-base text-yellow-800" aria-hidden="true">
                ↗
              </span>
              <span className="text-base font-medium text-zinc-900">{item.changeNote}</span>
            </div>
          )}

          <div className="flex items-center gap-4">
            <button
              type="button"
              disabled={!onViewResult}
              onClick={() => onViewResult?.(item)}
              className="cursor-pointer border-none bg-none p-0 text-base font-medium text-zinc-900 underline disabled:cursor-not-allowed disabled:text-neutral-400"
            >
              결과 보기
            </button>
            <button
              type="button"
              disabled={!onViewReport}
              onClick={() => onViewReport?.(item)}
              className="cursor-pointer border-none bg-none p-0 text-base font-medium text-zinc-900 underline disabled:cursor-not-allowed disabled:text-neutral-400"
            >
              보고서
            </button>
          </div>
        </>
      )}
    </div>
  );
}
