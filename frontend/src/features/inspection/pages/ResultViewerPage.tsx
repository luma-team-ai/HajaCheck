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
  const selected =
    selectedDefectId && visibleDefects.find((d) => d.id === selectedDefectId)
      ? visibleDefects.find((d) => d.id === selectedDefectId)
      : visibleDefects[0];

  const handleThresholdChange = (event: ChangeEvent<HTMLInputElement>) => {
    setConfidenceThreshold(Number(event.target.value));
  };

  const handleGradeToggle = (grade: DefectGrade) => {
    setGradeFilter((prev) =>
      prev.includes(grade) ? prev.filter((g) => g !== grade) : [...prev, grade],
    );
  };

  const progressPercent = (data.reviewedCount / data.totalCount) * 100;

  return (
    <div className="flex h-full flex-col">
      {/* Top Bar */}
      <div className="flex items-center justify-between border-b border-border px-6 py-4">
        <div className="flex items-center gap-2 text-sm text-text-muted">
          <span className="text-text-default font-medium">{data.defectCode}</span>
          <span>/</span>
          <span className="text-text-default font-medium">{data.facilityName}</span>
        </div>
        <div className="flex items-center gap-2 rounded-full bg-[#eff6ff] px-3 py-1.5">
          <div className="h-1.5 w-1.5 rounded-full bg-[#2563eb]" />
          <span className="text-xs font-medium text-[#2563eb]">{data.status}</span>
        </div>
      </div>

      {/* Main Content */}
      <div className="flex flex-1 gap-6 p-6">
        {/* Left: Image Viewer */}
        <div className="flex flex-1 flex-col gap-6">
          {/* Filter Controls */}
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
                  }`}
                >
                  <input
                    type="checkbox"
                    checked={gradeFilter.includes(grade)}
                    onChange={() => handleGradeToggle(grade)}
                    className="hidden"
                  />
                  {grade}
                </label>
              ))}
            </div>
          </div>

          {/* Image Card */}
          <div className="flex flex-1 flex-col gap-6 rounded-[48px] bg-[#f4f4f5] p-6">
            <DefectOverlay
              media={data.media}
              defects={visibleDefects}
              selectedId={selectedDefectId}
              onSelect={setSelectedDefectId}
            />

            {/* Progress Bar */}
            <div className="flex flex-col gap-3">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-text-muted">진행률</span>
                <span className="text-xs font-semibold text-text-default">
                  검수 {data.reviewedCount} / {data.totalCount}
                </span>
              </div>
              <div className="h-1 overflow-hidden rounded-full bg-[#e4e4e7]">
                <div
                  className="h-full bg-primary transition-all duration-300"
                  style={{ width: `${progressPercent}%` }}
                />
              </div>
            </div>

            {/* Button */}
            <Button type="button" variant="primary" size="lg" className="w-full">
              이 이미지 검수 확정
            </Button>
          </div>
        </div>

        {/* Right: Analysis Panel */}
        {selected && (
          <div className="flex w-80 flex-col border-l border-border">
            <div className="border-b border-border px-5 py-5">
              <h3 className="font-medium text-text-default">AI 분석 결과</h3>
            </div>
            <div className="flex-1 overflow-y-auto px-5 py-5">
              {/* Metadata Cards */}
              <div className="mb-6 flex gap-3">
                <div className="flex-1 rounded-[12px] border border-border bg-[#fafafa] p-4">
                  <div className="mb-2 text-xs text-text-muted">신뢰도</div>
                  <div className="text-xl font-bold text-text-default">
                    {Math.round(selected.confidence * 100)}%
                  </div>
                </div>
                <div className="flex-1 rounded-[12px] border border-border bg-[#fafafa] p-4">
                  <div className="mb-2 text-xs text-text-muted">예상 깊이</div>
                  <div className="text-xl font-bold text-text-default">{selected.depthMm}mm</div>
                </div>
              </div>

              {/* Summary Note */}
              <div>
                <div className="mb-3 flex items-center gap-2">
                  <svg className="h-[13px] w-[10px]" fill="currentColor" viewBox="0 0 10 13">
                    <path d="M5 0L6 3H10L7 5L8 8L5 6L2 8L3 5L0 3H4L5 0Z" />
                  </svg>
                  <span className="text-xs font-medium text-text-default">분석 요약</span>
                </div>
                <div className="rounded-xl border border-[#fef9c3] bg-[#fefce8] p-4 text-sm text-[#854d0e]">
                  {selected.summary}
                </div>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
