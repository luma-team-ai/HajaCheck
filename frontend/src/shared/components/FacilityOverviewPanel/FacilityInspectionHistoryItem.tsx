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
}

type Props = {
  item: FacilityOverviewHistoryItem;
  /** 최신 회차만 진하게 표시하고 썸네일/변화 메모/결과 링크까지 펼친다(Figma dev mode 마크업 기준) */
  expanded: boolean;
};

function formatDefectSummary(item: FacilityOverviewHistoryItem): string {
  const total = item.defectGradeBreakdown.reduce((sum, entry) => sum + entry.count, 0);
  const breakdown = item.defectGradeBreakdown
    .map((entry) => `${entry.grade} ${entry.count}`)
    .join(' · ');
  return `이미지 ${item.imageCount}장 · 하자 ${total}건 (${breakdown})`;
}

// 시설물 상세/점검(회차) 생성 화면이 공유하는 점검 이력 타임라인 항목(shared — 두 feature가 동일 UI를 쓴다).
export function FacilityInspectionHistoryItem({ item, expanded }: Props) {
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
          {item.additionalImageCount !== undefined && (
            <div className="flex items-center gap-3">
              <div className="size-24 rounded-xl bg-neutral-100 outline outline-1 outline-offset-[-1px] outline-neutral-300/30" />
              <div className="size-24 rounded-xl bg-neutral-100 outline outline-1 outline-offset-[-1px] outline-neutral-300/30" />
              <div className="flex size-24 items-center justify-center rounded-xl bg-zinc-200/30 text-base font-medium text-neutral-600 outline outline-1 outline-offset-[-1px] outline-neutral-300/30">
                +{item.additionalImageCount}
              </div>
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
            <button type="button" className="cursor-pointer border-none bg-none p-0 text-base font-medium text-zinc-900 underline">
              결과 보기
            </button>
            <button type="button" className="cursor-pointer border-none bg-none p-0 text-base font-medium text-zinc-900 underline">
              보고서
            </button>
          </div>
        </>
      )}
    </div>
  );
}
