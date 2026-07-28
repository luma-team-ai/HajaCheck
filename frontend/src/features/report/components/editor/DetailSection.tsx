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
  return SEVERITY_PILL_CLASS[grade] ?? 'border-border bg-surface-muted text-text-default';
}

const INLINE_INPUT_CLASSES =
  'w-full rounded-lg border border-transparent bg-transparent px-1 py-0.5 text-base font-semibold text-heading outline-none transition focus:border-primary focus:bg-surface focus:ring-2 focus:ring-primary/10 disabled:cursor-not-allowed read-only:text-heading';

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
        <h2 className="text-xl font-medium leading-7 text-heading">상세 내역</h2>
        <div className="flex flex-wrap items-center gap-3">
          <span className="text-xs font-medium tracking-wide text-text-default">등급:</span>
          <div className="flex flex-wrap items-center gap-1" role="group" aria-label="등급 필터">
            {visibleGradeFilters.map((filterGrade) => (
              <button
                key={filterGrade}
                type="button"
                onClick={() => selectFilter(filterGrade)}
                aria-pressed={grade === filterGrade}
                className={`inline-flex min-w-8 items-center justify-center rounded-full px-3 py-1 text-xs transition ${
                  grade === filterGrade
                    ? 'bg-primary font-medium text-surface'
                    : 'border border-border bg-surface text-heading hover:bg-surface-muted'
                }`}
              >
                {filterGrade === 'ALL' ? '전체' : filterGrade}
              </button>
            ))}
          </div>
          <div className="ml-1 flex items-center gap-2 text-xs text-text-muted">
            <button
              type="button"
              onClick={() => setPage((current) => Math.max(0, current - 1))}
              disabled={safePage === 0}
              aria-label="이전 페이지"
              className="inline-flex h-8 w-8 items-center justify-center rounded-full border border-border bg-surface text-text-default disabled:opacity-35"
            >
              ‹
            </button>
            <span aria-live="polite">
              <strong className="text-heading">{safePage + 1}</strong> / {totalPages}
            </span>
            <button
              type="button"
              onClick={() => setPage((current) => Math.min(totalPages - 1, current + 1))}
              disabled={safePage === totalPages - 1}
              aria-label="다음 페이지"
              className="inline-flex h-8 w-8 items-center justify-center rounded-full border border-border bg-surface text-text-default disabled:opacity-35"
            >
              ›
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
            <article
              id={`report-defect-${index + 1}`}
              key={index}
              className="overflow-hidden rounded-lg border border-border bg-surface"
            >
              <div className="grid gap-0 lg:grid-cols-[minmax(220px,280px)_minmax(0,1fr)]">
                <div className="relative min-h-56 overflow-hidden border-b border-border bg-surface-sunken lg:border-b-0 lg:border-r">
                  <DefectImage
                    src={imageUrls[index]}
                    alt={`지적 ${index + 1} 현장 이미지`}
                  />
                  <div className="absolute left-3 top-3 inline-flex items-center gap-2 rounded-lg border border-border bg-surface/85 px-3 py-1.5 text-xs font-semibold tracking-wide text-heading backdrop-blur-[10px]">
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

                <div className="flex min-w-0 flex-col">
                  <div className="grid gap-px bg-border md:grid-cols-3">
                    <label className="flex min-w-0 flex-col gap-1 bg-surface-muted px-4 py-3">
                      <span className="text-xs font-medium tracking-wide text-text-default">지적 유형</span>
                      <input
                        aria-label={`지적 ${index + 1} 유형`}
                        className={INLINE_INPUT_CLASSES}
                        value={item.defect_type}
                        disabled={readOnly}
                        onChange={(event) => updateItem(index, { defect_type: event.target.value })}
                      />
                    </label>
                    <label className="flex min-w-0 flex-col gap-1 bg-surface-muted px-4 py-3">
                      <span className="text-xs font-medium tracking-wide text-text-default">위치</span>
                      <input
                        aria-label={`지적 ${index + 1} 위치`}
                        className={INLINE_INPUT_CLASSES}
                        value={item.location}
                        disabled={readOnly}
                        onChange={(event) => updateItem(index, { location: event.target.value })}
                      />
                    </label>
                    <label className="flex min-w-0 flex-col gap-2 bg-surface-muted px-4 py-3">
                      <span className="text-xs font-medium tracking-wide text-text-default">등급</span>
                      <input
                        aria-label={`지적 ${index + 1} 등급`}
                        className={`w-24 rounded-full border px-3 py-1 text-center text-sm font-medium outline-none focus:ring-2 focus:ring-primary/10 disabled:cursor-not-allowed ${gradePillClass(
                          item.severity_grade,
                        )}`}
                        value={item.severity_grade}
                        disabled={readOnly}
                        onChange={(event) => updateItem(index, { severity_grade: event.target.value })}
                      />
                    </label>
                  </div>

                  <div className="grid gap-5 p-5 md:grid-cols-2">
                    <LabeledTextArea
                      label="설명"
                      value={item.description}
                      readOnly={readOnly}
                      rows={3}
                      textareaClassName="min-h-20 border-transparent bg-transparent px-1 py-1 focus:border-primary focus:bg-surface read-only:bg-transparent read-only:text-heading"
                      onChange={(value) => updateItem(index, { description: value })}
                    />
                    <LabeledTextArea
                      label="원인 분석"
                      value={item.cause}
                      readOnly={readOnly}
                      rows={3}
                      textareaClassName="min-h-20 border-transparent bg-transparent px-1 py-1 text-text-default focus:border-primary focus:bg-surface read-only:bg-transparent"
                      onChange={(value) => updateItem(index, { cause: value })}
                    />
                    {!readOnly && (
                      <div className="flex justify-end md:col-span-2">
                        <Button variant="secondary" size="sm" onClick={() => removeItem(index)}>
                          이 항목 삭제
                        </Button>
                      </div>
                    )}
                  </div>
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
