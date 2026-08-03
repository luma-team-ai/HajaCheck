import { useState } from "react";
import type { RecommendationItem, ReportContent } from "../../types";
import { LabeledTextArea } from "./LabeledTextArea";

interface RecommendationSectionProps {
  content: ReportContent;
  onChange: (next: ReportContent) => void;
  readOnly: boolean;
}

function priorityPillClass(priority: string): string {
  const normalized = priority.trim();
  if (/^(높|상|high|urgent)/i.test(normalized)) {
    return "border-red-200 bg-red-50 text-red-700";
  }
  if (/^(낮|하|low)/i.test(normalized)) {
    return "border-emerald-200 bg-emerald-50 text-emerald-700";
  }
  return "border-border bg-surface-muted text-text-default";
}

function formatPriorityLabel(priority: string): string {
  const normalized = priority.trim();
  if (/^(높|상|고|high|urgent)/i.test(normalized)) return "보수 시급성: 고";
  if (/^(낮|하|저|low)/i.test(normalized)) return "보수 시급성: 저";
  if (/^(중|보통|medium|normal)/i.test(normalized)) return "보수 시급성: 중";
  return normalized ? `보수 시급성: ${normalized}` : "보수 시급성: 미정";
}

// LabeledTextArea 기본 FIELD_CLASSES(px-4 py-3 등)와 충돌하는 속성은 `!`로 강제 우선시킨다
// (Tailwind 캐스케이드는 클래스 순서가 아니라 스케일 순서를 따르므로 px-0만으로는 안 이긴다).
const INLINE_TEXTAREA_CLASS =
  "min-h-0 border-transparent! bg-transparent! px-0! py-0! leading-6 focus:border-primary focus:bg-surface read-only:bg-transparent! read-only:text-heading!";

const READONLY_TARGET_TEXTAREA_CLASS =
  "min-h-0 border-transparent! bg-transparent! px-0! py-0! text-base font-medium leading-6 text-heading! read-only:bg-transparent! read-only:text-heading!";

const noop = () => {};

const PAGE_SIZE = 4;

export function RecommendationSection({
  content,
  onChange,
  readOnly,
}: RecommendationSectionProps) {
  const [page, setPage] = useState(0);
  const items = content.recommendation.items;

  const totalPages = Math.max(1, Math.ceil(items.length / PAGE_SIZE));
  const safePage = Math.min(Math.max(0, page), totalPages - 1);
  const indexedItems = items.map((item, index) => ({ item, index }));
  const pageItems = indexedItems.slice(
    safePage * PAGE_SIZE,
    safePage * PAGE_SIZE + PAGE_SIZE,
  );

  const updateItem = (index: number, patch: Partial<RecommendationItem>) => {
    const next = items.map((item, itemIndex) =>
      itemIndex === index ? { ...item, ...patch } : item,
    );
    onChange({
      ...content,
      recommendation: { ...content.recommendation, items: next },
    });
  };

  const moveToDefect = (index: number) => {
    document
      .getElementById(`report-defect-${index + 1}`)
      ?.scrollIntoView({ behavior: "smooth", block: "center" });
  };

  return (
    <section className="flex flex-col gap-6">
      {items.length > 0 && (
        <div className="flex items-center justify-end">
          <div className="ml-1 flex items-center gap-1">
            <button
              type="button"
              onClick={() => setPage((current) => Math.max(0, current - 1))}
              disabled={safePage === 0}
              aria-label="이전 페이지"
              className="size-8 rounded-full border border-zinc-200 flex justify-center items-center text-zinc-700 disabled:opacity-35 hover:bg-zinc-100 transition cursor-pointer"
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
            <div
              className="px-2 flex items-center gap-1 text-xs"
              aria-live="polite"
            >
              <span className="font-bold text-zinc-900">{safePage + 1}</span>
              <span className="text-zinc-500 font-normal">/ {totalPages}</span>
            </div>
            <button
              type="button"
              onClick={() =>
                setPage((current) => Math.min(totalPages - 1, current + 1))
              }
              disabled={safePage === totalPages - 1}
              aria-label="다음 페이지"
              className="size-8 rounded-full border border-zinc-200 flex justify-center items-center text-zinc-700 disabled:opacity-35 hover:bg-zinc-100 transition cursor-pointer"
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

      {items.length === 0 ? (
        <div className="rounded-lg border border-border bg-surface p-8 text-center text-sm text-text-muted">
          보수ㆍ보강 항목이 없습니다.
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          {pageItems.map(({ item, index }) => (
            <article
              key={index}
              className="flex flex-col gap-6 rounded-lg border border-border bg-surface px-7 py-6"
            >
              <div className="flex flex-wrap items-start justify-between gap-3">
                <span
                  aria-label={`권고 ${index + 1} 보수 시급성`}
                  className={`inline-flex min-h-7 max-w-full items-center rounded-full border px-3 py-1 text-xs font-medium ${priorityPillClass(
                    item.priority,
                  )}`}
                >
                  {formatPriorityLabel(item.priority)}
                </span>
                <button
                  type="button"
                  onClick={() => moveToDefect(index)}
                  className="rounded-full bg-text-default px-3 py-1.5 text-xs font-bold text-surface transition hover:bg-heading"
                >
                  하자 #{String(index + 1).padStart(2, "0")}
                </button>
              </div>

              <div className="grid gap-5">
                <LabeledTextArea
                  label="대상"
                  value={item.target}
                  readOnly
                  ariaLabel={`권고 ${index + 1} 대상`}
                  rows={1}
                  className="flex flex-col gap-1.5"
                  labelClassName="text-text-muted"
                  textareaClassName={READONLY_TARGET_TEXTAREA_CLASS}
                  onChange={noop}
                />
                <LabeledTextArea
                  label="방법"
                  value={item.method}
                  readOnly={readOnly}
                  rows={2}
                  className="flex flex-col gap-1.5"
                  textareaClassName={INLINE_TEXTAREA_CLASS}
                  onChange={(value) => updateItem(index, { method: value })}
                />
                <LabeledTextArea
                  label={`법적 근거${item.legal_basis_verified ? " (검증됨)" : ""}`}
                  value={item.legal_basis}
                  readOnly
                  rows={2}
                  className="flex flex-col gap-1.5"
                  textareaClassName={`${INLINE_TEXTAREA_CLASS} text-text-default`}
                  onChange={noop}
                />
              </div>
            </article>
          ))}
        </div>
      )}

      {content.recommendation.monitoring_points.length > 0 && (
        <div className="rounded-lg border border-border bg-surface p-5">
          <p className="mb-2 text-xs font-medium tracking-wide text-text-default">
            모니터링 포인트
          </p>
          <ul className="flex flex-col gap-1 text-sm text-heading">
            {content.recommendation.monitoring_points.map((point, index) => (
              <li key={index}>· {point}</li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
