import { useEffect, useState } from 'react';
import type { DefectDetailItem, ReportContent } from '../../types';
import { LabeledTextArea } from './LabeledTextArea';

type GradeFilter = 'ALL' | 'A' | 'B' | 'C' | 'D' | 'E';

const PAGE_SIZE = 2;

// 점검 요약 및 보고서 생성 페이지(ReportEntryPage)와 동일한 등급 배지 색상 스펙
const GRADE_BADGE_STYLE: Record<string, { bg: string; text: string }> = {
  A: { bg: '#e3f5e6', text: '#16a34a' },
  B: { bg: '#eef6df', text: '#65a30d' },
  C: { bg: '#fef9c3', text: '#a16207' },
  D: { bg: '#ffedd5', text: '#c2410c' },
  E: { bg: '#fef2f2', text: '#dc2626' },
};

const INLINE_INPUT_CLASSES =
  'w-full rounded-lg border border-transparent bg-transparent px-0 py-0 text-base font-semibold leading-6 text-heading outline-none transition focus:border-primary focus:bg-surface focus:px-2 focus:py-1 focus:ring-2 focus:ring-primary/10 disabled:cursor-not-allowed read-only:text-heading';

const INLINE_TEXTAREA_CLASSES =
  'min-h-0 border-transparent bg-transparent px-0 py-0 text-sm leading-6 focus:border-primary focus:bg-surface focus:px-2 focus:py-1 read-only:bg-transparent read-only:text-heading';

interface DetailSectionProps {
  content: ReportContent;
  onChange: (next: ReportContent) => void;
  readOnly: boolean;
  imageUrls?: Array<string | null | undefined>;
}

function DefectImage({ src, alt }: { src?: string | null; alt: string }) {
  const [failed, setFailed] = useState(false);

  if (!src || failed) {
    return (
      <div className="flex h-full min-h-56 items-center justify-center bg-surface-sunken text-sm text-text-muted">
        이미지 없음
      </div>
    );
  }

  return (
    <img
      src={src}
      alt={alt}
      className="h-full min-h-56 w-full object-cover"
      onError={() => setFailed(true)}
    />
  );
}

export function DetailSection({
  content,
  onChange,
  readOnly,
  imageUrls = [],
}: DetailSectionProps) {
  const [grade, setGrade] = useState<GradeFilter>('ALL');
  const [page, setPage] = useState(0);
  const [visibleImageUrls, setVisibleImageUrls] = useState(imageUrls);

  useEffect(() => {
    setVisibleImageUrls(imageUrls);
  }, [imageUrls]);

  const items = content.detail.items;
  const indexedItems = items.map((item, index) => ({ item, index }));
  const filtered =
    grade === 'ALL'
      ? indexedItems
      : indexedItems.filter(({ item }) => item.severity_grade === grade);
  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(Math.max(0, page), totalPages - 1);
  const pageItems = filtered.slice(safePage * PAGE_SIZE, safePage * PAGE_SIZE + PAGE_SIZE);
  const visibleGradeFilters: GradeFilter[] = ['ALL', 'A', 'B', 'C', 'D', 'E'];

  const updateItem = (index: number, patch: Partial<DefectDetailItem>) => {
    const next = items.map((item, itemIndex) =>
      itemIndex === index ? { ...item, ...patch } : item,
    );
    onChange({ ...content, detail: { items: next } });
  };

  const selectFilter = (next: GradeFilter) => {
    setGrade(next);
    setPage(0);
  };

  const getGradeCount = (g: GradeFilter) => {
    if (g === 'ALL') return items.length;
    return items.filter((item) => item.severity_grade === g).length;
  };

  const GRADE_DOT_COLOR: Record<string, string> = {
    A: 'bg-green-600',
    B: 'bg-lime-600',
    C: 'bg-yellow-500',
    D: 'bg-orange-500',
    E: 'bg-red-600',
  };

  return (
    <section className="flex flex-col gap-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <h2 className="text-xl font-medium leading-7 text-heading">상세 내역</h2>
        <div className="inline-flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-2.5">
            <span className="text-xs font-medium tracking-wide text-zinc-700">등급:</span>
          </div>

          <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="등급 필터">
            {visibleGradeFilters.map((filterGrade) => {
              const isSelected = grade === filterGrade;
              const count = getGradeCount(filterGrade);
              const dotColor = GRADE_DOT_COLOR[filterGrade];

              if (filterGrade === 'ALL') {
                return (
                  <button
                    key="ALL"
                    type="button"
                    onClick={() => selectFilter('ALL')}
                    aria-pressed={isSelected}
                    className={`inline-flex items-center justify-center rounded-full px-3 py-1.5 text-xs font-medium transition cursor-pointer ${
                      isSelected
                        ? 'bg-black text-white'
                        : 'bg-neutral-50 border border-zinc-200 text-zinc-900 hover:bg-zinc-100'
                    }`}
                  >
                    전체 ({count})
                  </button>
                );
              }

              return (
                <button
                  key={filterGrade}
                  type="button"
                  onClick={() => selectFilter(filterGrade)}
                  aria-pressed={isSelected}
                  className={`inline-flex items-center gap-2 rounded-full px-3 py-1.5 text-xs font-medium transition cursor-pointer border ${
                    isSelected
                      ? 'bg-black text-white border-black'
                      : 'bg-neutral-50 text-zinc-900 border-zinc-200 hover:bg-zinc-100'
                  }`}
                >
                  <span className={`size-2 rounded-full shrink-0 ${dotColor}`} />
                  <span>
                    {filterGrade} ({count})
                  </span>
                </button>
              );
            })}
          </div>

          <div className="ml-1 flex items-center gap-1">
            <button
              type="button"
              onClick={() => setPage((current) => Math.max(0, current - 1))}
              disabled={safePage === 0}
              aria-label="이전 페이지"
              className="size-8 rounded-full border border-zinc-200 flex justify-center items-center text-zinc-700 disabled:opacity-35 hover:bg-zinc-100 transition cursor-pointer"
            >
              <svg className="size-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <path d="m15 18-6-6 6-6"/>
              </svg>
            </button>
            <div className="px-2 flex items-center gap-1 text-xs" aria-live="polite">
              <span className="font-bold text-zinc-900">{safePage + 1}</span>
              <span className="text-zinc-500 font-normal">/ {totalPages}</span>
            </div>
            <button
              type="button"
              onClick={() => setPage((current) => Math.min(totalPages - 1, current + 1))}
              disabled={safePage === totalPages - 1}
              aria-label="다음 페이지"
              className="size-8 rounded-full border border-zinc-200 flex justify-center items-center text-zinc-700 disabled:opacity-35 hover:bg-zinc-100 transition cursor-pointer"
            >
              <svg className="size-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <path d="m9 18 6-6-6-6"/>
              </svg>
            </button>
          </div>
        </div>
      </div>

      {pageItems.length === 0 ? (
        <div className="rounded-lg border border-border bg-surface p-8 text-center text-sm text-text-muted">
          해당 등급의 지적 내역이 없습니다.
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          {pageItems.map(({ item, index }) => (
            <article id={`report-defect-${index + 1}`} key={index} className="overflow-hidden bg-surface">
              <div className="grid gap-0 border-y border-border lg:grid-cols-[minmax(240px,325px)_minmax(200px,236px)_minmax(0,1fr)]">
                <div className="relative min-h-72 overflow-hidden bg-surface-sunken">
                  <DefectImage
                    src={visibleImageUrls[index]}
                    alt={`지적 ${index + 1} 현장 이미지`}
                  />
                  <div className="absolute left-4 top-4 inline-flex items-center gap-2 rounded-full bg-surface/90 px-3 py-1.5 text-xs font-semibold tracking-wide text-heading backdrop-blur-[10px]">
                    <span
                      className={`h-2 w-2 rounded-full ${
                        item.severity_grade === 'A'
                          ? 'bg-emerald-500'
                          : item.severity_grade === 'C'
                            ? 'bg-orange-500'
                            : 'bg-primary'
                      }`}
                      aria-hidden="true"
                    />
                    DEFECT #{String(index + 1).padStart(2, '0')}
                  </div>
                </div>

                <div className="grid min-w-0 content-start gap-5 border-t border-border px-8 py-8 lg:border-l lg:border-t-0">
                  <label className="flex min-w-0 flex-col gap-1">
                      <span className="text-xs font-medium tracking-wide text-text-muted">지적 유형</span>
                      <input
                        aria-label={`지적 ${index + 1} 유형`}
                        className={INLINE_INPUT_CLASSES}
                        value={item.defect_type}
                        disabled={readOnly}
                        onChange={(event) => updateItem(index, { defect_type: event.target.value })}
                      />
                  </label>
                  <label className="flex min-w-0 flex-col gap-1">
                      <span className="text-xs font-medium tracking-wide text-text-muted">위치</span>
                      <input
                        aria-label={`지적 ${index + 1} 위치`}
                        className={INLINE_INPUT_CLASSES}
                        value={item.location}
                        disabled={readOnly}
                        onChange={(event) => updateItem(index, { location: event.target.value })}
                      />
                  </label>
                  <label className="flex min-w-0 flex-col gap-2">
                      <span className="text-xs font-medium tracking-wide text-text-muted">등급</span>
                      <input
                        aria-label={`지적 ${index + 1} 등급`}
                        className="w-24 rounded-full px-3 py-1 text-center text-sm font-bold border-0 outline-none focus:ring-2 focus:ring-primary/10 disabled:cursor-not-allowed"
                        style={
                          GRADE_BADGE_STYLE[item.severity_grade]
                            ? {
                                backgroundColor: GRADE_BADGE_STYLE[item.severity_grade].bg,
                                color: GRADE_BADGE_STYLE[item.severity_grade].text,
                              }
                            : { backgroundColor: '#f4f4f5', color: '#52525b' }
                        }
                        value={item.severity_grade}
                        disabled={readOnly}
                        onChange={(event) => updateItem(index, { severity_grade: event.target.value })}
                      />
                  </label>
                </div>

                <div className="grid min-w-0 content-start gap-5 border-t border-border px-8 py-8 lg:border-l lg:border-t-0">
                    <LabeledTextArea
                      label="설명"
                      value={item.description}
                      readOnly={readOnly}
                      rows={2}
                      textareaClassName={INLINE_TEXTAREA_CLASSES}
                      onChange={(value) => updateItem(index, { description: value })}
                    />
                    <LabeledTextArea
                      label="원인 분석"
                      value={item.cause}
                      readOnly={readOnly}
                      rows={2}
                      textareaClassName={`${INLINE_TEXTAREA_CLASSES} text-text-default`}
                      onChange={(value) => updateItem(index, { cause: value })}
                    />
                  </div>
                </div>
            </article>
          ))}
        </div>
      )}
    </section>
  );
}
