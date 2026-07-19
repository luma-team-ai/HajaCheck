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

// ponytail: 하자 편집 액션(오탐삭제/등급수정/누락추가) 목업 스타일 — 백엔드 미구현이라 onClick 없음, #249 후속 이슈에서 연동
const DANGER_PILL_CLASSES =
  'inline-flex items-center justify-center gap-1.5 rounded-full border border-danger-soft-border bg-danger-soft-bg px-6 py-3.5 text-base font-semibold text-danger transition hover:bg-danger-soft-hover';

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
    <div className="flex h-full flex-col gap-4 py-6 pl-6 pr-28">
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

      {/* Unified Card: header + image viewer + AI panel share one rounded border (Figma 참조 — 카드 하나로 연결) */}
      <div className="flex flex-1 flex-col overflow-hidden rounded-3xl border border-border">
        {/* Header — 이미지/AI패널 두 컬럼 위에 걸치는 공통 헤더(Figma에서 한 줄로 이어짐) */}
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

        <div className="flex flex-1">
          {/* Left: Image Viewer */}
          <div className="flex flex-1 flex-col gap-6 bg-[#f4f4f5] p-6">
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

            {/* Actions — 오탐삭제:검수확정 = 3:7 비율 */}
            <div className="flex items-center gap-3">
              <button type="button" className={`flex-[3] ${DANGER_PILL_CLASSES}`}>
                오탐 삭제
              </button>
              <Button type="button" variant="primary" size="lg" className="flex-[7]">
                이 이미지 검수 확정
              </Button>
            </div>
          </div>

          {/* Right: Analysis Panel */}
          {selected && (
            <div className="flex w-1/4 min-w-[260px] flex-col border-l border-border bg-white">
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

              {/* 하자 편집 액션 — 선택된 하자 컨텍스트라 AI 분석 결과 패널 하단에 배치 */}
              <div className="flex gap-3 px-5 pt-4 pb-6">
                <Button type="button" variant="secondary" size="lg" className="flex-1">
                  등급 수정
                </Button>
                <Button type="button" variant="secondary" size="lg" className="flex-1">
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
