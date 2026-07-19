import type { ChangeEvent } from 'react';
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { AIErrorFallback } from '../../../shared/components/AIErrorFallback';
import { AILoadingIndicator } from '../../../shared/components/AILoadingIndicator';
import { Button } from '../../../shared/components/Button';
import { DefectOverlay } from '../components/DefectOverlay';
import { useInspectionResult } from '../hooks/useInspectionResult';
import type { DefectGrade } from '../types';
import { filterDefects } from '../utils/filterDefects';

const ALL_GRADES: DefectGrade[] = ['A', 'B', 'C', 'D', 'E'];

export function ResultViewerPage() {
  const { id } = useParams<{ id: string }>();
  const inspectionId = Number(id);
  const [confidenceThreshold, setConfidenceThreshold] = useState(0.5);
  const [gradeFilter, setGradeFilter] = useState<DefectGrade[]>(ALL_GRADES);
  const [selectedDefectId, setSelectedDefectId] = useState<number | undefined>();

  if (!Number.isInteger(inspectionId) || inspectionId <= 0) {
    return (
      <div className="p-5 text-red-600">잘못된 접근입니다. 유효한 검사 ID를 확인하세요.</div>
    );
  }

  const { data, isLoading, isError, refetch } = useInspectionResult(inspectionId);

  if (isLoading) return <AILoadingIndicator message="점검 결과를 분석 중입니다..." />;
  if (isError) return <AIErrorFallback onRetry={() => void refetch()} />;
  if (!data || data.defects.length === 0)
    return <div className="p-5">탐지된 하자가 없습니다.</div>;

  const visibleDefects = filterDefects(data.defects, confidenceThreshold, gradeFilter);
  const found = selectedDefectId
    ? visibleDefects.find((d) => d.id === selectedDefectId)
    : undefined;
  const selected = found ?? visibleDefects[0];

  const handleThresholdChange = (event: ChangeEvent<HTMLInputElement>) => {
    setConfidenceThreshold(Number(event.target.value));
  };

  const handleGradeToggle = (grade: DefectGrade) => {
    setGradeFilter((prev) =>
      prev.includes(grade) ? prev.filter((g) => g !== grade) : [...prev, grade],
    );
  };

  const progressPercent = data.totalCount > 0 ? (data.reviewedCount / data.totalCount) * 100 : 0;

  return (
    <div className="flex h-full flex-col gap-4 py-6 pl-6 pr-28">
      {/* Filter Controls — Top Level */}
      <div className="flex gap-4">
        <div className="flex items-center gap-2">
          <label className="text-xs text-text-muted">신뢰도:</label>
          <input
            type="range"
            min={0}
            max={1}
            step={0.05}
            value={confidenceThreshold}
            onChange={handleThresholdChange}
            className="h-1 w-24 cursor-pointer"
          />
          <span className="text-xs font-medium">{confidenceThreshold.toFixed(2)}</span>
        </div>
        <div className="flex gap-2">
          {ALL_GRADES.map((grade) => (
            <label
              key={grade}
              className={`cursor-pointer rounded-full px-2.5 py-1 text-xs font-medium transition ${
                gradeFilter.includes(grade)
                  ? 'bg-primary text-surface'
                  : 'border border-border bg-white text-text-default'
              } has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-primary has-[:focus-visible]:outline-none`}
            >
              <input
                type="checkbox"
                checked={gradeFilter.includes(grade)}
                onChange={() => handleGradeToggle(grade)}
                className="sr-only"
              />
              {grade}
            </label>
          ))}
        </div>
      </div>

      {/* Unified Card — 헤더가 좌(이미지)/우(AI패널) 두 컬럼 위에 한 줄로 걸침(Figma 정합) */}
      <div className="flex flex-1 flex-col overflow-hidden rounded-3xl border border-border">
        {/* Card Header */}
        <div className="flex items-center justify-between border-b border-border px-6 py-4">
          <div className="flex items-center gap-2 text-sm text-text-muted">
            <span className="text-text-default font-medium">{data.defectCode}</span>
            <span>/</span>
            <span className="text-text-default font-medium">{data.facilityName}</span>
          </div>
          <div className="flex items-center gap-2 rounded-full bg-info-soft-bg px-3 py-1.5">
            <div className="h-1.5 w-1.5 rounded-full bg-info-soft-fg" />
            <span className="text-xs font-medium text-info-soft-fg">{data.status}</span>
          </div>
        </div>

        <div className="flex flex-1">
          {/* Left: Image Viewer Section */}
          <div className="flex flex-1 flex-col gap-6 bg-surface-sunken p-6">
            <div className="flex flex-1 items-center justify-center">
              {visibleDefects.length === 0 ? (
                <div className="text-sm text-text-muted">조건에 맞는 하자가 없습니다.</div>
              ) : (
                <DefectOverlay
                  media={data.media}
                  defects={visibleDefects}
                  selectedId={selected?.id}
                  onSelect={setSelectedDefectId}
                />
              )}
            </div>

            {/* Progress Bar */}
            <div className="flex flex-col gap-3">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-text-muted">진행률</span>
                <span className="text-xs font-semibold text-text-default">
                  검수 {data.reviewedCount} / {data.totalCount}
                </span>
              </div>
              <div className="h-1 overflow-hidden rounded-full bg-border">
                <div
                  className="h-full bg-primary transition-all duration-300"
                  style={{ width: `${progressPercent}%` }}
                />
              </div>
            </div>

            {/* Action Buttons — 우측 패널의 등급수정/누락추가와 동일 높이로 하단 정렬 */}
            {/* TODO: 백엔드 구현(#16 오탐 수정·등급 조정, #17 하자 상태머신) 후 활성화 — #249 후속 이슈 */}
            {visibleDefects.length > 0 && (
              <div className="flex items-center gap-3">
                <Button type="button" variant="danger-soft" size="lg" className="flex-[3]" disabled>
                  오탐 삭제
                </Button>
                <Button type="button" variant="primary" size="lg" className="flex-[7]" disabled>
                  이 이미지 검수 확정
                </Button>
              </div>
            )}
          </div>

          {/* Right: Analysis Panel */}
          {selected && (
            <div className="flex w-80 flex-col border-l border-border">
              <div className="px-5 py-5">
                <h3 className="font-medium text-text-default">AI 분석 결과</h3>
              </div>
              <div className="flex-1 overflow-y-auto px-5">
                {/* Metadata Cards */}
                <div className="mb-6 flex gap-3">
                  <div className="flex-1 rounded-[12px] border border-border bg-surface-muted p-4">
                    <div className="mb-2 text-xs text-text-muted">신뢰도</div>
                    <div className="text-xl font-bold text-text-default">
                      {Math.round(selected.confidence * 100)}%
                    </div>
                  </div>
                  {/* 유형별 정량 실측 지표 — 균열은 선형(폭/길이 mm), 박리박락·철근노출은 면적형(마스크 면적 비율)
                      (하자_심각도_등급_규칙.md §3.2, PRD v0.42 탐지 클래스 3종 확정) */}
                  {selected.type === '균열' ? (
                    <div className="flex-1 rounded-[12px] border border-border bg-surface-muted p-4">
                      <div className="mb-2 text-xs text-text-muted">예상 길이</div>
                      <div className="text-xl font-bold text-text-default">{selected.lengthMm}mm</div>
                    </div>
                  ) : (
                    <div className="flex-1 rounded-[12px] border border-border bg-surface-muted p-4">
                      <div className="mb-2 text-xs text-text-muted">면적 비율</div>
                      <div className="text-xl font-bold text-text-default">
                        {Math.round((selected.areaRatio ?? 0) * 100)}%
                      </div>
                    </div>
                  )}
                </div>

                {/* Summary Note */}
                <div>
                  <div className="mb-3 flex items-center gap-2">
                    <svg className="h-[13px] w-[10px]" fill="currentColor" viewBox="0 0 10 13">
                      <path d="M5 0L6 3H10L7 5L8 8L5 6L2 8L3 5L0 3H4L5 0Z" />
                    </svg>
                    <span className="text-xs font-medium text-text-default">분석 요약</span>
                  </div>
                  <div className="rounded-xl border border-warning-soft-border bg-warning-soft-bg p-4 text-sm text-warning-soft-fg">
                    {selected.summary}
                  </div>
                </div>
              </div>

              {/* Action Buttons — 스크롤 영역 밖 하단 고정, 좌측 "검수 확정" 버튼과 동일 높이 */}
              {/* TODO: 백엔드 구현(#16 오탐 수정·등급 조정, #17 하자 상태머신) 후 활성화 — #249 후속 이슈 */}
              {/* 좌측 컬럼(p-6=24px 하단 여백)과 하단 정렬 맞추려 pb-6 사용, py-5 아님 */}
              <div className="flex gap-3 px-5 pt-5 pb-6">
                <Button type="button" variant="secondary" size="lg" className="flex-1" disabled>
                  등급 수정
                </Button>
                <Button type="button" variant="secondary" size="lg" className="flex-1" disabled>
                  누락 추가
                </Button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
