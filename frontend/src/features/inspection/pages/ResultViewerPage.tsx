import type { ChangeEvent } from 'react';
import { useState, useCallback } from 'react';
import { useParams } from 'react-router-dom';
import { AIErrorFallback } from '../../../shared/components/AIErrorFallback';
import { AILoadingIndicator } from '../../../shared/components/AILoadingIndicator';
import { Button } from '../../../shared/components/Button';
import { DefectOverlay } from '../components/DefectOverlay';
import { InspectionDefectExplainPanel } from '../components/InspectionDefectExplainPanel';
import { useInspectionResult } from '../hooks/useInspectionResult';
import { inspectionApi } from '../api/inspectionApi';
import type { DefectGrade } from '../types';
import { filterDefects } from '../utils/filterDefects';

const ALL_GRADES: DefectGrade[] = ['A', 'B', 'C', 'D', 'E'];

export function ResultViewerPage() {
  const { id } = useParams<{ id: string }>();
  const inspectionId = Number(id);
  const [confidenceThreshold, setConfidenceThreshold] = useState(0.5);
  const [gradeFilter, setGradeFilter] = useState<DefectGrade[]>(ALL_GRADES);
  const [selectedDefectId, setSelectedDefectId] = useState<number | undefined>();
  const [gradeEditId, setGradeEditId] = useState<number | undefined>();
  const [selectedGrade, setSelectedGrade] = useState<DefectGrade | ''>('');
  // rules-of-hooks: 훅은 조건부 return 이전에 호출해야 한다. 훅 내부 enabled 플래그가
  // 유효하지 않은 inspectionId일 때 쿼리를 스킵하므로, ID 검증 return은 훅 호출 다음에 둔다.
  const { data, isLoading, isError, refetch } = useInspectionResult(inspectionId);
  const [isUpdating, setIsUpdating] = useState(false);

  if (!Number.isInteger(inspectionId) || inspectionId <= 0) {
    return (
      <div className="p-5 text-red-600">잘못된 접근입니다. 유효한 검사 ID를 확인하세요.</div>
    );
  }

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

  // 오탐 삭제: 선택된 하자를 isDeleted: true로 마크
  const handleDeleteFalsePositive = useCallback(async () => {
    if (!selected || isUpdating) return;
    setIsUpdating(true);
    try {
      await inspectionApi.reviewDefect(selected.id, { isDeleted: true });
      // 성공 후 데이터 갱신
      await refetch();
    } catch (error) {
      console.error('오탐 삭제 실패:', error);
    } finally {
      setIsUpdating(false);
    }
  }, [selected, isUpdating, refetch]);

  // 등급 수정: 선택된 등급을 명시적으로 선택 후 저장
  const handleOpenGradeEdit = useCallback(() => {
    if (selected) {
      setGradeEditId(selected.id);
      setSelectedGrade(selected.grade);
    }
  }, [selected]);

  const handleConfirmGrade = useCallback(async () => {
    if (!selected || !selectedGrade || isUpdating) return;
    setIsUpdating(true);
    try {
      await inspectionApi.reviewDefect(selected.id, { grade: selectedGrade as DefectGrade });
      await refetch();
      setGradeEditId(undefined);
      setSelectedGrade('');
    } catch (error) {
      console.error('등급 수정 실패:', error);
    } finally {
      setIsUpdating(false);
    }
  }, [selected, selectedGrade, isUpdating, refetch]);

  const handleCancelGradeEdit = useCallback(() => {
    setGradeEditId(undefined);
    setSelectedGrade('');
  }, []);

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
            {visibleDefects.length > 0 && (
              <div className="flex items-center gap-3">
                <Button
                  type="button"
                  variant="danger-soft"
                  size="lg"
                  className="flex-[3]"
                  onClick={handleDeleteFalsePositive}
                  disabled={isUpdating || !selected}
                >
                  오탐 삭제
                </Button>
                {/* TODO: 검수 확정 → 검수 API 확정 또는 inspection 상태 transition API 필요 (#17/PR #372 검토 필요) */}
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

                {/* AI Analysis Panel */}
                <div>
                  <div className="mb-3 flex items-center gap-2">
                    <svg className="h-[13px] w-[10px]" fill="currentColor" viewBox="0 0 10 13">
                      <path d="M5 0L6 3H10L7 5L8 8L5 6L2 8L3 5L0 3H4L5 0Z" />
                    </svg>
                    <span className="text-xs font-medium text-text-default">분석 요약</span>
                  </div>
                  {data && (
                    <InspectionDefectExplainPanel
                      defectType={selected.type}
                      grade={selected.grade}
                      facilityType={data.facilityType}
                    />
                  )}
                </div>
              </div>

              {/* Grade Edit Mode */}
              {gradeEditId === selected.id ? (
                <div className="flex gap-2 px-5 pt-5 pb-6">
                  <select
                    value={selectedGrade}
                    onChange={(e) => setSelectedGrade(e.target.value as DefectGrade | '')}
                    className="flex-1 rounded-lg border border-border bg-white px-3 py-2 text-sm"
                  >
                    <option value="">등급 선택</option>
                    {ALL_GRADES.map((g) => (
                      <option key={g} value={g}>
                        {g}
                      </option>
                    ))}
                  </select>
                  <Button
                    type="button"
                    variant="primary"
                    size="lg"
                    className="flex-1"
                    onClick={handleConfirmGrade}
                    disabled={!selectedGrade || isUpdating}
                  >
                    저장
                  </Button>
                  <Button
                    type="button"
                    variant="secondary"
                    size="lg"
                    className="flex-1"
                    onClick={handleCancelGradeEdit}
                    disabled={isUpdating}
                  >
                    취소
                  </Button>
                </div>
              ) : (
                <div className="flex gap-3 px-5 pt-5 pb-6">
                  <Button
                    type="button"
                    variant="secondary"
                    size="lg"
                    className="flex-1"
                    onClick={handleOpenGradeEdit}
                    disabled={isUpdating || !selected}
                  >
                    등급 수정
                  </Button>
                  {/* TODO: 누락 추가 → defect 생성 API 미구현 (#249 후속) */}
                  <Button type="button" variant="secondary" size="lg" className="flex-1" disabled>
                    누락 추가
                  </Button>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
