import { useState } from 'react';
import type { DefectDetailItem, ReportContent } from '../../types';
import { Button } from '../../../../shared/components/Button';
import { LabeledTextArea } from './LabeledTextArea';

type GradeFilter = 'ALL' | 'A' | 'B' | 'C' | 'D' | 'E';
type Grade = Exclude<GradeFilter, 'ALL'>;

const PAGE_SIZE = 2;
const GRADES: Grade[] = ['A', 'B', 'C', 'D', 'E'];
const FIGMA_DEFAULT_GRADES = new Set<Grade>(['A', 'B', 'C']);

const SEVERITY_PILL_CLASS: Record<string, string> = {
  A: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  B: 'border-lime-200 bg-lime-50 text-lime-700',
  C: 'border-amber-200 bg-amber-50 text-amber-700',
  D: 'border-orange-200 bg-orange-50 text-orange-700',
  E: 'border-red-200 bg-red-50 text-red-700',
};

function gradePillClass(grade: string): string {
  return SEVERITY_PILL_CLASS[grade] ?? 'border-zinc-200 bg-zinc-50 text-zinc-700';
}

const INLINE_INPUT_CLASSES =
  'w-full rounded-lg border border-transparent bg-transparent px-1 py-0.5 text-base font-semibold text-zinc-900 outline-none transition focus:border-zinc-300 focus:bg-white focus:ring-2 focus:ring-zinc-100 disabled:cursor-not-allowed read-only:text-zinc-900';

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
      <div className="flex h-full min-h-56 items-center justify-center bg-zinc-100 text-sm text-zinc-500">
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

  const items = content.detail.items;
  const indexedItems = items.map((item, index) => ({ item, index }));
  const filtered =
    grade === 'ALL'
      ? indexedItems
      : indexedItems.filter(({ item }) => item.severity_grade === grade);
  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(Math.max(0, page), totalPages - 1);
  const pageItems = filtered.slice(safePage * PAGE_SIZE, safePage * PAGE_SIZE + PAGE_SIZE);
  const visibleGradeFilters: GradeFilter[] = [
    'ALL',
    ...GRADES.filter(
      (candidate) =>
        FIGMA_DEFAULT_GRADES.has(candidate) ||
        items.some((item) => item.severity_grade === candidate),
    ),
  ];

  const updateItem = (index: number, patch: Partial<DefectDetailItem>) => {
    const next = items.map((item, itemIndex) =>
      itemIndex === index ? { ...item, ...patch } : item,
    );
    onChange({ ...content, detail: { items: next } });
  };

  const removeItem = (index: number) => {
    const next = items.filter((_, itemIndex) => itemIndex !== index);
    onChange({ ...content, detail: { items: next } });
    setPage((previous) => {
      const newTotal = Math.max(1, Math.ceil(next.length / PAGE_SIZE));
      return Math.min(previous, newTotal - 1);
    });
  };

  const addItem = () => {
    const blank: DefectDetailItem = {
      defect_type: '',
      location: '',
      severity_grade: '',
      description: '',
      cause: '',
    };
    onChange({ ...content, detail: { items: [...items, blank] } });
    setPage(Math.max(0, Math.ceil((items.length + 1) / PAGE_SIZE) - 1));
  };

  const selectFilter = (next: GradeFilter) => {
    setGrade(next);
    setPage(0);
  };

  return (
    <section className="flex flex-col gap-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <h2 className="text-xl font-medium leading-7 text-zinc-900">상세 내역</h2>
        <div className="flex flex-wrap items-center gap-3">
          <span className="text-xs font-medium tracking-wide text-zinc-700">등급:</span>
          <div className="flex flex-wrap items-center gap-1" role="group" aria-label="등급 필터">
            {visibleGradeFilters.map((filterGrade) => (
              <button
                key={filterGrade}
                type="button"
                onClick={() => selectFilter(filterGrade)}
                aria-pressed={grade === filterGrade}
                className={`inline-flex min-w-8 items-center justify-center rounded-full px-3 py-1 text-xs transition ${
                  grade === filterGrade
                    ? 'bg-black font-medium text-white'
                    : 'border border-zinc-200 bg-white text-zinc-900 hover:bg-zinc-50'
                }`}
              >
                {filterGrade === 'ALL' ? '전체' : filterGrade}
              </button>
            ))}
          </div>
          <div className="ml-1 flex items-center gap-2 text-xs text-zinc-500">
            <button
              type="button"
              onClick={() => setPage((current) => Math.max(0, current - 1))}
              disabled={safePage === 0}
              aria-label="이전 페이지"
              className="inline-flex h-8 w-8 items-center justify-center rounded-full border border-zinc-200 bg-white text-zinc-700 disabled:opacity-35"
            >
              ‹
            </button>
            <span aria-live="polite">
              <strong className="text-zinc-900">{safePage + 1}</strong> / {totalPages}
            </span>
            <button
              type="button"
              onClick={() => setPage((current) => Math.min(totalPages - 1, current + 1))}
              disabled={safePage === totalPages - 1}
              aria-label="다음 페이지"
              className="inline-flex h-8 w-8 items-center justify-center rounded-full border border-zinc-200 bg-white text-zinc-700 disabled:opacity-35"
            >
              ›
            </button>
          </div>
        </div>
      </div>

      {pageItems.length === 0 ? (
        <div className="rounded-2xl border border-zinc-200 bg-white p-8 text-center text-sm text-zinc-500">
          해당 등급의 지적 내역이 없습니다.
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          {pageItems.map(({ item, index }) => (
            <article
              id={`report-defect-${index + 1}`}
              key={index}
              className="overflow-hidden rounded-2xl border border-zinc-200 bg-white lg:grid lg:grid-cols-[minmax(240px,320px)_minmax(0,1fr)]"
            >
              <div className="relative min-h-56 overflow-hidden bg-zinc-100">
                <DefectImage
                  src={imageUrls[index]}
                  alt={`지적 ${index + 1} 현장 이미지`}
                />
                <div className="absolute left-4 top-4 inline-flex items-center gap-2 rounded-full border border-white/30 bg-white/75 px-3 py-1.5 text-xs font-semibold tracking-wide text-zinc-900 backdrop-blur-[10px]">
                  <span
                    className={`h-2 w-2 rounded-full ${
                      item.severity_grade === 'A'
                        ? 'bg-emerald-500'
                        : item.severity_grade === 'C'
                          ? 'bg-orange-500'
                          : 'bg-zinc-500'
                    }`}
                    aria-hidden="true"
                  />
                  DEFECT #{String(index + 1).padStart(2, '0')}
                </div>
              </div>

              <div className="grid gap-6 p-6 lg:grid-cols-[160px_minmax(0,1fr)] lg:p-8">
                <div className="flex flex-col gap-4">
                  <label className="flex flex-col gap-1">
                    <span className="text-xs font-medium tracking-wide text-zinc-700">지적 유형</span>
                    <input
                      aria-label={`지적 ${index + 1} 유형`}
                      className={INLINE_INPUT_CLASSES}
                      value={item.defect_type}
                      disabled={readOnly}
                      onChange={(event) => updateItem(index, { defect_type: event.target.value })}
                    />
                  </label>
                  <label className="flex flex-col gap-1">
                    <span className="text-xs font-medium tracking-wide text-zinc-700">위치</span>
                    <input
                      aria-label={`지적 ${index + 1} 위치`}
                      className={INLINE_INPUT_CLASSES}
                      value={item.location}
                      disabled={readOnly}
                      onChange={(event) => updateItem(index, { location: event.target.value })}
                    />
                  </label>
                  <label className="flex flex-col gap-2">
                    <span className="text-xs font-medium tracking-wide text-zinc-700">등급</span>
                    <input
                      aria-label={`지적 ${index + 1} 등급`}
                      className={`w-24 rounded-full border px-3 py-1 text-center text-sm font-medium outline-none focus:ring-2 focus:ring-zinc-200 disabled:cursor-not-allowed ${gradePillClass(
                        item.severity_grade,
                      )}`}
                      value={item.severity_grade}
                      disabled={readOnly}
                      onChange={(event) => updateItem(index, { severity_grade: event.target.value })}
                    />
                  </label>
                </div>

                <div className="flex min-w-0 flex-col gap-4 lg:border-l lg:border-zinc-200 lg:pl-6">
                  <LabeledTextArea
                    label="설명"
                    value={item.description}
                    readOnly={readOnly}
                    rows={3}
                    textareaClassName="min-h-20 border-transparent bg-transparent px-1 py-1 focus:border-zinc-300 focus:bg-white read-only:bg-transparent read-only:text-zinc-900"
                    onChange={(value) => updateItem(index, { description: value })}
                  />
                  <LabeledTextArea
                    label="원인 분석"
                    value={item.cause}
                    readOnly={readOnly}
                    rows={3}
                    textareaClassName="min-h-20 border-transparent bg-transparent px-1 py-1 text-zinc-700 focus:border-zinc-300 focus:bg-white read-only:bg-transparent"
                    onChange={(value) => updateItem(index, { cause: value })}
                  />
                  {!readOnly && (
                    <div className="flex justify-end">
                      <Button variant="secondary" size="sm" onClick={() => removeItem(index)}>
                        이 항목 삭제
                      </Button>
                    </div>
                  )}
                </div>
              </div>
            </article>
          ))}
        </div>
      )}

      {!readOnly && (
        <div>
          <Button variant="secondary" size="sm" onClick={addItem}>
            + 상세 항목 추가
          </Button>
        </div>
      )}
    </section>
  );
}
