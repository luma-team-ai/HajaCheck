import { useEffect, useState } from "react";
import type { DefectDetailItem, ReportContent } from "../../types";
import { DefectPhoto, type DefectPhotoGroup } from "./DefectPhoto";
import { LabeledTextArea } from "./LabeledTextArea";

type GradeFilter = "ALL" | "A" | "B" | "C" | "D" | "E";

const PAGE_SIZE = 2;

// 점검 요약 및 보고서 생성 페이지(ReportEntryPage)와 동일한 등급 배지 색상 스펙
const GRADE_BADGE_STYLE: Record<string, { bg: string; text: string }> = {
  A: { bg: "#e3f5e6", text: "#16a34a" },
  B: { bg: "#eef6df", text: "#65a30d" },
  C: { bg: "#fef9c3", text: "#a16207" },
  D: { bg: "#ffedd5", text: "#c2410c" },
  E: { bg: "#fef2f2", text: "#dc2626" },
};

// LabeledTextArea 기본 FIELD_CLASSES(px-4 py-3 border-border bg-surface 등)와 같은 속성을 덮어써야
// 하는데, Tailwind는 클래스 문자열 순서가 아니라 유틸리티가 생성된 스케일 순서로 캐스케이드를
// 결정한다(px-4가 px-0보다 나중에 정의돼 실제로는 px-4가 이긴다) — 그래서 충돌하는 속성엔 `!`를
// 붙여 항상 이기도록 강제한다(다른 곳의 StatisticsFilterBar.tsx 관례와 동일).
const INLINE_TEXTAREA_CLASSES =
  "min-h-0 border-transparent! bg-transparent! px-0! py-0! text-sm leading-6 focus:border-primary focus:bg-surface read-only:bg-transparent! read-only:text-heading!";

const READONLY_FACT_TEXTAREA_CLASSES =
  "min-h-0 border-transparent! bg-transparent! px-0! py-0! text-left text-base! font-semibold leading-6 text-heading! read-only:bg-transparent! read-only:text-heading!";

const noop = () => {};

interface DetailSectionProps {
  content: ReportContent;
  onChange: (next: ReportContent) => void;
  readOnly: boolean;
  /**
   * 하자 상세 항목과 **같은 순서·같은 개수**로 정렬된 사진 — `content.detail.items[i]`가
   * `defectPhotos[i]`에 대응한다. 각 그룹은 그 사진의 하자를 모두 담되 해당 항목만 강조한다(#1333).
   */
  defectPhotos?: DefectPhotoGroup[];
}

function DefectImagePlaceholder() {
  return (
    <div className="flex h-full min-h-56 items-center justify-center bg-surface-sunken text-sm text-text-muted">
      이미지 없음
    </div>
  );
}

function ReadOnlyFactField({
  label,
  ariaLabel,
  value,
}: {
  label: string;
  ariaLabel: string;
  value: string;
}) {
  return (
    <LabeledTextArea
      label={label}
      labelClassName="text-text-muted"
      className="min-w-0"
      value={value || "-"}
      readOnly
      ariaLabel={ariaLabel}
      rows={1}
      textareaClassName={READONLY_FACT_TEXTAREA_CLASSES}
      onChange={noop}
    />
  );
}

function GradeBadge({ grade }: { grade: string }) {
  const style = GRADE_BADGE_STYLE[grade] ?? { bg: "#f4f4f5", text: "#52525b" };
  return (
    <span
      aria-label={`등급 ${grade || "미정"}`}
      className="inline-flex w-fit min-w-16 items-center justify-center rounded-full px-3 py-1 text-center text-sm font-bold"
      style={{ backgroundColor: style.bg, color: style.text }}
    >
      {grade || "-"}
    </span>
  );
}

export function DetailSection({
  content,
  onChange,
  readOnly,
  defectPhotos = [],
}: DetailSectionProps) {
  const [grade, setGrade] = useState<GradeFilter>("ALL");
  const [page, setPage] = useState(0);
  const [visiblePhotos, setVisiblePhotos] = useState(defectPhotos);

  useEffect(() => {
    setVisiblePhotos(defectPhotos);
  }, [defectPhotos]);

  const items = content.detail.items;
  const indexedItems = items.map((item, index) => ({ item, index }));
  const filtered =
    grade === "ALL"
      ? indexedItems
      : indexedItems.filter(({ item }) => item.severity_grade === grade);
  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(Math.max(0, page), totalPages - 1);
  const pageItems = filtered.slice(
    safePage * PAGE_SIZE,
    safePage * PAGE_SIZE + PAGE_SIZE,
  );
  const visibleGradeFilters: GradeFilter[] = ["ALL", "A", "B", "C", "D", "E"];

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
    if (g === "ALL") return items.length;
    return items.filter((item) => item.severity_grade === g).length;
  };

  const GRADE_DOT_COLOR: Record<string, string> = {
    A: "bg-green-600",
    B: "bg-lime-600",
    C: "bg-yellow-500",
    D: "bg-orange-500",
    E: "bg-red-600",
  };

  return (
    <section className="flex flex-col gap-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-end">
        <div className="inline-flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-2.5">
            <span className="text-xs font-medium tracking-wide text-zinc-700">
              등급:
            </span>
          </div>

          <div
            className="flex flex-wrap items-center gap-1.5"
            role="group"
            aria-label="등급 필터"
          >
            {visibleGradeFilters.map((filterGrade) => {
              const isSelected = grade === filterGrade;
              const count = getGradeCount(filterGrade);
              const dotColor = GRADE_DOT_COLOR[filterGrade];

              if (filterGrade === "ALL") {
                return (
                  <button
                    key="ALL"
                    type="button"
                    onClick={() => selectFilter("ALL")}
                    aria-pressed={isSelected}
                    className={`inline-flex h-7 items-center justify-center rounded-full border px-3 py-1.5 text-xs font-medium transition cursor-pointer box-border ${
                      isSelected
                        ? "bg-black border-black text-white"
                        : "bg-neutral-50 border-zinc-200 text-zinc-900 hover:bg-zinc-100"
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
                  className={`inline-flex h-7 items-center justify-center gap-2 rounded-full border px-3 py-1.5 text-xs font-medium transition cursor-pointer box-border ${
                    isSelected
                      ? "bg-black border-black text-white"
                      : "bg-neutral-50 border-zinc-200 text-zinc-900 hover:bg-zinc-100"
                  }`}
                >
                  <span
                    className={`size-2 rounded-full shrink-0 ${dotColor}`}
                  />
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
      </div>

      {pageItems.length === 0 ? (
        <div className="rounded-lg border border-border bg-surface p-8 text-center text-sm text-text-muted">
          해당 등급의 하자 내역이 없습니다.
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          {pageItems.map(({ item, index }) => {
            const photoGroup = visiblePhotos[index];
            const scopedPhotoGroup =
              photoGroup && grade !== "ALL"
                ? {
                    ...photoGroup,
                    defects: photoGroup.defects.filter(
                      (defect) => defect.grade === grade,
                    ),
                    highlightDefectId: undefined,
                  }
                : photoGroup;
            const dotColor =
              GRADE_DOT_COLOR[item.severity_grade] ?? "bg-zinc-400";
            return (
              <article
                id={`report-defect-${index + 1}`}
                key={index}
                className="rounded-lg border border-border bg-surface overflow-hidden"
              >
                <div className="grid gap-0 lg:grid-cols-[minmax(240px,325px)_minmax(200px,236px)_minmax(0,1fr)]">
                  <div className="relative flex min-h-72 items-center justify-center overflow-hidden bg-surface-sunken">
                    {scopedPhotoGroup ? (
                      <DefectPhoto
                        group={scopedPhotoGroup}
                        alt={`하자 ${index + 1} 현장 이미지`}
                        imageClassName="max-h-[420px] w-auto max-w-full"
                        fallback={<DefectImagePlaceholder />}
                      />
                    ) : (
                      <DefectImagePlaceholder />
                    )}
                    <div className="absolute left-4 top-4 inline-flex items-center gap-2 rounded-full bg-surface/90 px-3 py-1.5 text-xs font-semibold tracking-wide text-heading backdrop-blur-[10px]">
                      <span
                        className={`h-2 w-2 rounded-full ${dotColor}`}
                        aria-hidden="true"
                      />
                      하자 #{String(index + 1).padStart(2, "0")}
                    </div>
                  </div>

                  <div className="grid min-w-0 content-start gap-5 border-t border-border px-8 py-8 lg:border-l lg:border-t-0">
                    <ReadOnlyFactField
                      label="하자 유형"
                      ariaLabel={`하자 ${index + 1} 유형`}
                      value={item.defect_type}
                    />
                    <ReadOnlyFactField
                      label="위치"
                      ariaLabel={`하자 ${index + 1} 위치`}
                      value={item.location}
                    />
                    <label className="flex min-w-0 flex-col gap-2">
                      <span className="text-xs font-medium tracking-wide text-text-muted">
                        등급
                      </span>
                      <GradeBadge grade={item.severity_grade} />
                    </label>
                  </div>

                  <div className="grid min-w-0 content-start gap-5 border-t border-border px-8 py-8 lg:border-l lg:border-t-0">
                    <LabeledTextArea
                      label="설명"
                      value={item.description}
                      readOnly={readOnly}
                      rows={2}
                      textareaClassName={INLINE_TEXTAREA_CLASSES}
                      onChange={(value) =>
                        updateItem(index, { description: value })
                      }
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
            );
          })}
        </div>
      )}
    </section>
  );
}
