import { useState } from 'react';
import type { DefectDetailItem, ReportContent } from '../../types';
import { Button } from '../../../../shared/components/Button';
import { LabeledTextArea } from './LabeledTextArea';

type GradeFilter = 'ALL' | 'A' | 'B' | 'C' | 'D' | 'E';

const PAGE_SIZE = 2;
const GRADE_FILTERS: GradeFilter[] = ['ALL', 'A', 'B', 'C', 'D', 'E'];

// 등급 색상 체계는 types.ts §56-58 주석 기준(A=양호 emerald → E=중대 red).
// Figma 시안의 색상(A=빨강)을 따르지 않는다 — 핸드오프 §주의 2.
const SEVERITY_PILL_CLASS: Record<string, string> = {
  A: 'bg-emerald-100 text-emerald-700',
  B: 'bg-lime-100 text-lime-700',
  C: 'bg-amber-100 text-amber-700',
  D: 'bg-orange-100 text-orange-700',
  E: 'bg-red-100 text-red-700',
};

function gradePillClass(grade: string): string {
  return SEVERITY_PILL_CLASS[grade] ?? 'bg-zinc-100 text-zinc-700';
}

const INLINE_INPUT_CLASSES =
  'w-full rounded-lg border border-border bg-surface px-2 py-1 text-sm text-text-default disabled:cursor-not-allowed disabled:bg-surface-muted disabled:text-text-muted';

interface DetailSectionProps {
  content: ReportContent;
  onChange: (next: ReportContent) => void;
  readOnly: boolean;
}

export function DetailSection({ content, onChange, readOnly }: DetailSectionProps) {
  const [grade, setGrade] = useState<GradeFilter>('ALL');
  const [page, setPage] = useState(0);

  const items = content.detail.items;
  const indexedItems = items.map((item, index) => ({ item, index }));
  const filtered =
    grade === 'ALL'
      ? indexedItems
      : indexedItems.filter((x) => x.item.severity_grade === grade);
  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(Math.max(0, page), totalPages - 1);
  const pageItems = filtered.slice(safePage * PAGE_SIZE, safePage * PAGE_SIZE + PAGE_SIZE);

  const updateItem = (index: number, patch: Partial<DefectDetailItem>) => {
    const next = items.map((it, i) => (i === index ? { ...it, ...patch } : it));
    onChange({ ...content, detail: { items: next } });
  };

  const removeItem = (index: number) => {
    const next = items.filter((_, i) => i !== index);
    onChange({ ...content, detail: { items: next } });
    // 삭제 후 페이지 보정 — 현재 페이지가 비어 있으면 한 페이지 앞으로 당김.
    setPage((prev) => {
      const newTotal = Math.max(1, Math.ceil(next.length / PAGE_SIZE));
      return Math.min(prev, newTotal - 1);
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
    // 새 항목 추가 시 마지막 페이지로 이동해 바로 보이게 함.
    const newTotal = Math.max(1, Math.ceil((items.length + 1) / PAGE_SIZE));
    setPage(newTotal - 1);
  };

  const selectFilter = (next: GradeFilter) => {
    setGrade(next);
    setPage(0);
  };

  return (
    <section className="flex flex-col gap-4 rounded-2xl border border-zinc-200 bg-white p-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-semibold text-text-default">상세 내역</h2>
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex items-center gap-1" role="group" aria-label="등급 필터">
            {GRADE_FILTERS.map((g) => (
              <button
                key={g}
                type="button"
                onClick={() => selectFilter(g)}
                aria-pressed={grade === g}
                className={
                  'rounded-full px-3 py-1 text-xs font-medium transition ' +
                  (grade === g
                    ? 'bg-black text-white'
                    : 'bg-zinc-100 text-text-muted hover:bg-zinc-200')
                }
              >
                {g === 'ALL' ? '전체' : g}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-2 text-xs text-text-muted">
            <button
              type="button"
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={safePage === 0}
              aria-label="이전 페이지"
              className="inline-flex h-6 w-6 items-center justify-center rounded-full border border-border bg-surface disabled:opacity-40"
            >
              {'<'}
            </button>
            <span aria-live="polite">
              {safePage + 1} / {totalPages}
            </span>
            <button
              type="button"
              onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
              disabled={safePage === totalPages - 1}
              aria-label="다음 페이지"
              className="inline-flex h-6 w-6 items-center justify-center rounded-full border border-border bg-surface disabled:opacity-40"
            >
              {'>'}
            </button>
          </div>
        </div>
      </div>

      {pageItems.length === 0 ? (
        <p className="text-sm text-text-muted">해당 등급의 지적 내역이 없습니다.</p>
      ) : (
        <div className="flex flex-col gap-4">
          {pageItems.map(({ item, index }) => (
            <article
              key={index}
              className="flex flex-col gap-4 rounded-2xl border border-zinc-200 bg-surface-muted p-4 sm:flex-row"
            >
              {/* 좌측 이미지 영역 — 실제 이미지 연계는 후속 작업에서. 단순 placeholder. */}
              <div
                className="h-32 w-full shrink-0 rounded-lg bg-zinc-200 sm:w-44"
                aria-hidden
              />
              <div className="flex flex-1 flex-col gap-3">
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                  <label className="flex flex-col gap-1">
                    <span className="text-xs font-medium text-text-muted">지적 유형</span>
                    <input
                      className={INLINE_INPUT_CLASSES}
                      value={item.defect_type}
                      disabled={readOnly}
                      onChange={(e) => updateItem(index, { defect_type: e.target.value })}
                    />
                  </label>
                  <label className="flex flex-col gap-1">
                    <span className="text-xs font-medium text-text-muted">위치</span>
                    <input
                      className={INLINE_INPUT_CLASSES}
                      value={item.location}
                      disabled={readOnly}
                      onChange={(e) => updateItem(index, { location: e.target.value })}
                    />
                  </label>
                  <label className="flex flex-col gap-1">
                    <span className="text-xs font-medium text-text-muted">등급</span>
                    <input
                      className={INLINE_INPUT_CLASSES}
                      value={item.severity_grade}
                      disabled={readOnly}
                      onChange={(e) => updateItem(index, { severity_grade: e.target.value })}
                    />
                  </label>
                </div>
                <div className="flex items-center gap-2 text-xs">
                  <span className="font-medium text-text-muted">등급 표시</span>
                  <span
                    className={
                      'rounded-md px-2 py-0.5 text-xs font-semibold ' +
                      gradePillClass(item.severity_grade)
                    }
                  >
                    {item.severity_grade || '미정'}
                  </span>
                </div>
                <LabeledTextArea
                  label="설명"
                  value={item.description}
                  readOnly={readOnly}
                  rows={2}
                  onChange={(v) => updateItem(index, { description: v })}
                />
                <LabeledTextArea
                  label="원인 분석"
                  value={item.cause}
                  readOnly={readOnly}
                  rows={2}
                  onChange={(v) => updateItem(index, { cause: v })}
                />
                {!readOnly && (
                  <Button variant="secondary" size="sm" onClick={() => removeItem(index)}>
                    이 항목 삭제
                  </Button>
                )}
              </div>
            </article>
          ))}
        </div>
      )}

      {!readOnly && (
        <Button variant="secondary" size="sm" onClick={addItem}>
          + 상세 항목 추가
        </Button>
      )}
    </section>
  );
}
