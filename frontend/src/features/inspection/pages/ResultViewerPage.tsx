import type { ChangeEvent } from 'react';
import { useState, useCallback, useEffect, useMemo } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { AIErrorFallback } from '../../../shared/components/AIErrorFallback';
import { AILoadingIndicator } from '../../../shared/components/AILoadingIndicator';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal/Modal';
import { DefectOverlay } from '../components/DefectOverlay';
import { InspectionDefectExplainPanel } from '../components/InspectionDefectExplainPanel';
import { useInspectionResult } from '../hooks/useInspectionResult';
import { inspectionApi } from '../api/inspectionApi';
import { useInspectionStore } from '../store/inspectionStore';
import type { DefectGrade } from '../types';
import { filterDefects } from '../utils/filterDefects';

const ALL_GRADES: DefectGrade[] = ['A', 'B', 'C', 'D', 'E'];
const DEFECT_TYPE_OPTIONS: { value: 'CRACK' | 'SPALLING' | 'LEAK_EFFLORESCENCE' | 'REBAR_EXPOSURE' | 'PAINT_DAMAGE'; label: string }[] = [
  { value: 'CRACK', label: '균열' },
  { value: 'SPALLING', label: '박리박락' },
  { value: 'LEAK_EFFLORESCENCE', label: '누수·백태' },
  { value: 'REBAR_EXPOSURE', label: '철근노출' },
  { value: 'PAINT_DAMAGE', label: '도장 손상' },
];

export function ResultViewerPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const inspectionId = Number(id);
  const setActiveInspectionId = useInspectionStore((state) => state.setActiveInspectionId);

  // 보고 있던 이미지/하자를 URL 쿼리로 들고 다닌다 — 검수 중 페이지를 이탈했다 재진입해도(뒤로가기,
  // 새로고침) 처음(이미지 1장)으로 되돌아가지 않도록(#784). 서버 데이터(진행률 등)는 원래도
  // 유실되지 않았고, 로컬 state(어떤 이미지를 보고 있었는지)만 컴포넌트 언마운트로 사라지던 것.
  const [searchParams, setSearchParams] = useSearchParams();
  const initialMediaIdParam = searchParams.get('mediaId');
  const initialDefectIdParam = searchParams.get('defectId');

  const [confidenceThreshold, setConfidenceThreshold] = useState(0.5);
  const [gradeFilter, setGradeFilter] = useState<DefectGrade[]>(ALL_GRADES);
  const [selectedDefectId, setSelectedDefectId] = useState<number | undefined>(
    initialDefectIdParam ? Number(initialDefectIdParam) : undefined,
  );
  // sentinel은 undefined("미선택")로 둔다 — 수동 추가 하자 그룹의 실제 mediaId도 null이라
  // null을 sentinel로 쓰면 그 그룹을 선택해도 "미선택"으로 오인되어 항상 첫 이미지로 되돌아간다.
  const [selectedMediaId, setSelectedMediaId] = useState<number | null | undefined>(
    initialMediaIdParam ? Number(initialMediaIdParam) : undefined,
  );

  // 선택 변경 시 URL에 반영(replace — 클릭마다 히스토리 스택을 쌓지 않는다).
  useEffect(() => {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (selectedMediaId != null) next.set('mediaId', String(selectedMediaId));
        else next.delete('mediaId');
        if (selectedDefectId != null) next.set('defectId', String(selectedDefectId));
        else next.delete('defectId');
        return next;
      },
      { replace: true },
    );
  }, [selectedMediaId, selectedDefectId, setSearchParams]);
  const [gradeEditId, setGradeEditId] = useState<number | undefined>();
  const [selectedGrade, setSelectedGrade] = useState<DefectGrade | ''>('');
  const [gradeReason, setGradeReason] = useState('');
  const [errorMessage, setErrorMessage] = useState('');
  const [isAddMissingOpen, setIsAddMissingOpen] = useState(false);
  const [newDefectType, setNewDefectType] = useState<'CRACK' | 'SPALLING' | 'LEAK_EFFLORESCENCE' | 'REBAR_EXPOSURE' | 'PAINT_DAMAGE' | ''>('');
  const [newDefectGrade, setNewDefectGrade] = useState<DefectGrade | ''>('');
  // rules-of-hooks: 훅은 조건부 return 이전에 호출해야 한다. 훅 내부 enabled 플래그가
  // 유효하지 않은 inspectionId일 때 쿼리를 스킵하므로, ID 검증 return은 훅 호출 다음에 둔다.
  const { data, isLoading, isError, refetch } = useInspectionResult(inspectionId);
  const [isUpdating, setIsUpdating] = useState(false);

  // 유효한 inspection id일 때 store에 저장 — SideNavBar의 동적 링크 생성에 사용
  useEffect(() => {
    if (Number.isInteger(inspectionId) && inspectionId > 0) {
      setActiveInspectionId(inspectionId);
    }
  }, [inspectionId, setActiveInspectionId]);

  // ponytail: 콜백은 훅이므로 조건부 return 이전에 정의(rules-of-hooks).
  // 콜백 내부에서 data/selected를 참조하지만, 클로저 캡처는 실행 시점에 일어나므로 정의 시점에 존재할 필요 없음.
  // 콜백 내부 guards가 조기 return을 처리한다.

  const handleDeleteFalsePositive = useCallback(async () => {
    if (!data) return;
    const reason = prompt('오탐 삭제 사유를 입력해주세요 (1-500자):');
    if (!reason || reason.trim().length === 0 || reason.trim().length > 500) {
      setErrorMessage('사유는 1-500자 범위여야 합니다.');
      return;
    }
    const visibleDefects = filterDefects(data.defects, confidenceThreshold, gradeFilter);
    const selected = selectedDefectId
      ? visibleDefects.find((d) => d.id === selectedDefectId)
      : visibleDefects[0];
    if (!selected || isUpdating) return;
    setIsUpdating(true);
    setErrorMessage('');
    try {
      await inspectionApi.reviewDefect(selected.id, { isDeleted: true, reason: reason.trim() });
      await refetch();
    } catch (error) {
      const msg = error instanceof Error ? error.message : '오탐 삭제에 실패했습니다.';
      setErrorMessage(msg);
    } finally {
      setIsUpdating(false);
    }
  }, [data, confidenceThreshold, gradeFilter, selectedDefectId, isUpdating, refetch]);

  const handleOpenGradeEdit = useCallback(() => {
    if (!data) return;
    const visibleDefects = filterDefects(data.defects, confidenceThreshold, gradeFilter);
    const selected = selectedDefectId
      ? visibleDefects.find((d) => d.id === selectedDefectId)
      : visibleDefects[0];
    if (selected) {
      setGradeEditId(selected.id);
      setSelectedGrade(selected.grade);
    }
  }, [data, confidenceThreshold, gradeFilter, selectedDefectId]);

  const handleConfirmGrade = useCallback(async () => {
    if (!data) return;
    if (!gradeReason.trim() || gradeReason.trim().length > 500) {
      setErrorMessage('수정 사유는 1-500자 범위여야 합니다.');
      return;
    }
    const visibleDefects = filterDefects(data.defects, confidenceThreshold, gradeFilter);
    const selected = selectedDefectId
      ? visibleDefects.find((d) => d.id === selectedDefectId)
      : visibleDefects[0];
    if (!selected || !selectedGrade || isUpdating) return;
    setIsUpdating(true);
    setErrorMessage('');
    try {
      await inspectionApi.reviewDefect(selected.id, {
        grade: selectedGrade as DefectGrade,
        reason: gradeReason.trim(),
      });
      await refetch();
      setGradeEditId(undefined);
      setSelectedGrade('');
      setGradeReason('');
    } catch (error) {
      const msg = error instanceof Error ? error.message : '등급 수정에 실패했습니다.';
      setErrorMessage(msg);
    } finally {
      setIsUpdating(false);
    }
  }, [data, confidenceThreshold, gradeFilter, selectedDefectId, selectedGrade, gradeReason, isUpdating, refetch]);

  const handleCancelGradeEdit = useCallback(() => {
    setGradeEditId(undefined);
    setSelectedGrade('');
  }, []);

  const handleCreateMissingDefect = useCallback(async () => {
    if (!newDefectType || !newDefectGrade || isUpdating) return;
    setIsUpdating(true);
    setErrorMessage('');
    try {
      const response = await inspectionApi.createDefect(inspectionId, {
        type: newDefectType as 'CRACK' | 'SPALLING' | 'LEAK_EFFLORESCENCE' | 'REBAR_EXPOSURE' | 'PAINT_DAMAGE',
        grade: newDefectGrade as DefectGrade,
      });
      await refetch();
      setSelectedDefectId(response.data.id);
      setIsAddMissingOpen(false);
      setNewDefectType('');
      setNewDefectGrade('');
    } catch (error) {
      const msg = error instanceof Error ? error.message : '누락 추가에 실패했습니다.';
      setErrorMessage(msg);
    } finally {
      setIsUpdating(false);
    }
  }, [inspectionId, newDefectType, newDefectGrade, isUpdating, refetch]);

  const handleCancelAddMissing = useCallback(() => {
    if (isUpdating) return;
    setIsAddMissingOpen(false);
    setNewDefectType('');
    setNewDefectGrade('');
    setErrorMessage('');
  }, [isUpdating]);

  const handleConfirmReview = useCallback(async () => {
    if (!data) return;
    const visibleDefects = filterDefects(data.defects, confidenceThreshold, gradeFilter);
    const selected = selectedDefectId
      ? visibleDefects.find((d) => d.id === selectedDefectId)
      : visibleDefects[0];
    if (!selected || isUpdating) return;
    setIsUpdating(true);
    setErrorMessage('');
    try {
      await inspectionApi.updateDefectStatus(selected.id, { status: 'CONFIRMED' });
      await refetch();
    } catch (error) {
      const msg = error instanceof Error ? error.message : '검수 확정에 실패했습니다.';
      setErrorMessage(msg);
    } finally {
      setIsUpdating(false);
    }
  }, [data, confidenceThreshold, gradeFilter, selectedDefectId, isUpdating, refetch]);

  const handleGenerateReport = useCallback(() => {
    navigate(`/inspections/${inspectionId}/reports/generate`);
  }, [inspectionId, navigate]);

  // rules-of-hooks: 모든 훅은 조건부 return 이전에 호출되어야 한다.
  // data가 없을 때도 안전하게 처리할 수 있도록 가드 포함.
  const visibleDefects = data?.defects
    ? filterDefects(data.defects, confidenceThreshold, gradeFilter)
    : [];

  // ponytail: mediaId별 그룹핑 — 각 이미지의 고유 mediaId와 해당 imageUrl 추출.
  // 수동 추가 하자(mediaId=null)는 애초에 특정 이미지에 결부되지 않는 API 설계라(#784) 이미지
  // 순회 대상에서 제외한다 — 넣으면 "다음 이미지"가 이미지 없는 깨진 화면으로 넘어가 버림.
  // 뷰어에서 수동 추가 하자를 어떻게 노출할지는 팀 판단 대기(#784).
  const mediaGroups = useMemo(() => {
    const groups = new Map<number, { mediaId: number; imageUrl: string | null; defects: typeof visibleDefects }>();
    for (const defect of visibleDefects) {
      if (defect.mediaId == null) continue;
      const mId = defect.mediaId;
      if (!groups.has(mId)) {
        groups.set(mId, { mediaId: mId, imageUrl: defect.imageUrl ?? null, defects: [] });
      }
      groups.get(mId)?.defects.push(defect);
    }
    return Array.from(groups.values()).sort((a, b) => a.mediaId - b.mediaId);
  }, [visibleDefects]);

  // 현재 선택된 media(또는 첫 번째 media)
  const currentMediaGroup = useMemo(() => {
    if (mediaGroups.length === 0) return null;
    const currentId = selectedMediaId !== undefined ? selectedMediaId : (mediaGroups[0]?.mediaId ?? null);
    return mediaGroups.find((g) => g.mediaId === currentId) ?? mediaGroups[0] ?? null;
  }, [mediaGroups, selectedMediaId]);

  // 현재 media 그룹의 defects (early return 이전 계산)
  const currentDefects = currentMediaGroup?.defects ?? [];

  // 현재 media 인디케이터 (예: "이미지 1/2")
  const currentMediaIndex = mediaGroups.findIndex((g) => g.mediaId === currentMediaGroup?.mediaId);
  const mediaIndicator = mediaGroups.length > 0 ? `이미지 ${currentMediaIndex + 1}/${mediaGroups.length}` : '';

  // 이전/다음 이미지 네비게이션 — rules-of-hooks: 훅은 조건부 return 이전에 호출
  const handlePrevMedia = useCallback(() => {
    if (currentMediaIndex > 0) {
      setSelectedMediaId(mediaGroups[currentMediaIndex - 1]?.mediaId ?? null);
      setSelectedDefectId(undefined);
    }
  }, [currentMediaIndex, mediaGroups]);

  const handleNextMedia = useCallback(() => {
    if (currentMediaIndex < mediaGroups.length - 1) {
      setSelectedMediaId(mediaGroups[currentMediaIndex + 1]?.mediaId ?? null);
      setSelectedDefectId(undefined);
    }
  }, [currentMediaIndex, mediaGroups]);

  if (!Number.isInteger(inspectionId) || inspectionId <= 0) {
    return (
      <div className="p-5 text-red-600">잘못된 접근입니다. 유효한 검사 ID를 확인하세요.</div>
    );
  }

  if (isLoading) return <AILoadingIndicator message="점검 결과를 분석 중입니다..." />;
  if (isError) return <AIErrorFallback onRetry={() => void refetch()} />;
  if (!data || data.defects.length === 0)
    return <div className="p-5">탐지된 하자가 없습니다.</div>;

  const found = selectedDefectId
    ? currentDefects.find((d) => d.id === selectedDefectId)
    : undefined;
  const selected = found ?? currentDefects[0];

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
      {/* Header with Generate Report Button */}
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-text-default">점검 결과 분석</h2>
        <Button
          type="button"
          variant="secondary"
          size="md"
          onClick={handleGenerateReport}
        >
          보고서 생성
        </Button>
      </div>

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
            {/* Image Navigator — 다중 이미지 지원 */}
            {mediaGroups.length > 1 && (
              <div className="flex items-center justify-between">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={handlePrevMedia}
                  disabled={currentMediaIndex === 0}
                >
                  ← 이전 이미지
                </Button>
                <span className="text-xs font-medium text-text-muted">{mediaIndicator}</span>
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={handleNextMedia}
                  disabled={currentMediaIndex === mediaGroups.length - 1}
                >
                  다음 이미지 →
                </Button>
              </div>
            )}

            <div className="flex flex-1 items-center justify-center">
              {currentDefects.length === 0 ? (
                <div className="text-sm text-text-muted">
                  {mediaGroups.length === 0 ? '조건에 맞는 하자가 없습니다.' : '이 이미지에 해당하는 하자가 없습니다.'}
                </div>
              ) : (
                <DefectOverlay
                  media={{
                    id: currentMediaGroup?.mediaId ?? 0,
                    imageUrl: currentMediaGroup?.imageUrl ?? '',
                  }}
                  defects={currentDefects}
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
              <div className="flex flex-col gap-2">
                {errorMessage && (
                  <div className="rounded-lg bg-red-100 p-3 text-sm text-red-700">{errorMessage}</div>
                )}
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
                <Button
                  type="button"
                  variant="primary"
                  size="lg"
                  className="flex-[7]"
                  onClick={handleConfirmReview}
                  disabled={isUpdating || !selected || selected.status !== 'DETECTED'}
                >
                  이 하자 검수 확정
                </Button>
                </div>
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
                        {selected.areaRatio !== undefined ? `${Math.round(selected.areaRatio * 100)}%` : '준비 중'}
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
                <div className="flex flex-col gap-2 px-5 pt-5 pb-6">
                  {errorMessage && (
                    <div className="rounded-lg bg-red-100 p-3 text-sm text-red-700">{errorMessage}</div>
                  )}
                  <div className="flex gap-2">
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
                  </div>
                  <textarea
                    value={gradeReason}
                    onChange={(e) => setGradeReason(e.target.value)}
                    placeholder="수정 사유를 입력해주세요 (1-500자)"
                    maxLength={500}
                    className="rounded-lg border border-border bg-white px-3 py-2 text-sm"
                    rows={3}
                  />
                  <div className="flex gap-2">
                    <Button
                      type="button"
                      variant="primary"
                      size="lg"
                      className="flex-1"
                      onClick={handleConfirmGrade}
                      disabled={!selectedGrade || !gradeReason.trim() || isUpdating}
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
                  <Button
                    type="button"
                    variant="secondary"
                    size="lg"
                    className="flex-1"
                    onClick={() => {
                      setIsAddMissingOpen(true);
                      setErrorMessage('');
                    }}
                    disabled={isUpdating}
                  >
                    누락 추가
                  </Button>
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Add Missing Defect Modal */}
      <Modal
        open={isAddMissingOpen}
        onClose={handleCancelAddMissing}
        title="누락된 하자 추가"
        closeOnOverlayClick={!isUpdating}
      >
        <div className="flex flex-col gap-4">
          {/* 이미지 위 위치(bbox) 지정 UI가 없다는 걸 명시 — 현재 API도 이 경로로는 mediaId를
              받지 않아 특정 이미지에 결부되지 않는다(#784). 위치 지정까지 지원할지는 팀 논의 후 별도 작업. */}
          <p className="text-xs text-text-muted">
            유형·등급만 기록되며, 특정 이미지의 위치(박스)에는 연결되지 않습니다.
          </p>
          {errorMessage && (
            <div className="rounded-lg bg-red-100 p-3 text-sm text-red-700">{errorMessage}</div>
          )}
          <div>
            <label htmlFor="defect-type-select" className="mb-2 block text-sm font-medium text-text-default">하자 유형</label>
            <select
              id="defect-type-select"
              value={newDefectType}
              onChange={(e) => setNewDefectType(e.target.value as 'CRACK' | 'SPALLING' | 'LEAK_EFFLORESCENCE' | 'REBAR_EXPOSURE' | 'PAINT_DAMAGE' | '')}
              className="w-full rounded-lg border border-border bg-white px-3 py-2 text-sm"
            >
              <option value="">유형 선택</option>
              {DEFECT_TYPE_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor="defect-grade-select" className="mb-2 block text-sm font-medium text-text-default">등급</label>
            <select
              id="defect-grade-select"
              value={newDefectGrade}
              onChange={(e) => setNewDefectGrade(e.target.value as DefectGrade | '')}
              className="w-full rounded-lg border border-border bg-white px-3 py-2 text-sm"
            >
              <option value="">등급 선택</option>
              {ALL_GRADES.map((g) => (
                <option key={g} value={g}>
                  {g}
                </option>
              ))}
            </select>
          </div>
          <div className="flex gap-2 pt-2">
            <Button
              type="button"
              variant="primary"
              size="lg"
              className="flex-1"
              onClick={handleCreateMissingDefect}
              disabled={!newDefectType || !newDefectGrade || isUpdating}
            >
              저장
            </Button>
            <Button
              type="button"
              variant="secondary"
              size="lg"
              className="flex-1"
              onClick={handleCancelAddMissing}
              disabled={isUpdating}
            >
              취소
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
