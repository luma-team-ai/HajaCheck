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
import { findSelectedDefect } from '../utils/findSelectedDefect';

const ALL_GRADES: DefectGrade[] = ['A', 'B', 'C', 'D', 'E'];

// 누락 추가 캔버스 — 드래그 없이 클릭만 해도 제출되던 0크기 박스 방지 임계값(정규화 좌표 기준, #841)
const MIN_BBOX_SIZE = 0.01;

// Figma 시안의 등급 라벨은 이 페이지 전용 워딩이다 — feature 간 직접 import 금지(types.ts 참고).
// StatisticsGradeDistributionCard와 동일 라벨 사용.
const GRADE_LABELS: Record<DefectGrade, string> = {
  A: 'A (경미)',
  B: 'B (양호)',
  C: 'C (보통)',
  D: 'D (주의)',
  E: 'E (심각)',
};

// 등급별 색상 점 — 프로젝트 표준 팔레트(dashboard/colors.ts GRADE_BG_CLASS,
// map/constants.ts GRADE_COLOR, charts/palette.ts CHART_GRADE_COLORS)와 동일 값(#957).
// B는 '#84CC16'(등급분포 막대 전용 "연한" 팔레트)을 잘못 가져왔던 것을 표준값으로 정정.
const GRADE_DOT_COLORS: Record<DefectGrade, string> = {
  A: '#16A34A',
  B: '#65A30D',
  C: '#EAB308',
  D: '#F97316',
  E: '#DC2626',
};

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
  // 누락 추가 그리기 모드 — 메인 뷰어 이미지 위에서 직접 드래그로 박스를 지정한다(#874, 2안).
  const [isDrawingMissing, setIsDrawingMissing] = useState(false);
  const [newDefectType, setNewDefectType] = useState<'CRACK' | 'SPALLING' | 'LEAK_EFFLORESCENCE' | 'REBAR_EXPOSURE' | 'PAINT_DAMAGE' | ''>('');
  const [newDefectGrade, setNewDefectGrade] = useState<DefectGrade | ''>('');
  // ponytail: 캔버스 드래그 상태 — 그리기 모드 진입/모달 닫힘마다 리셋
  const [draggingBbox, setDraggingBbox] = useState<{ x: number; y: number; width: number; height: number } | undefined>();
  const [canvasMouseDown, setCanvasMouseDown] = useState(false);
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

  // rules-of-hooks: 모든 훅은 조건부 return 이전에 호출되어야 한다.
  // data가 없을 때도 안전하게 처리할 수 있도록 가드 포함.
  const visibleDefects = data?.defects
    ? filterDefects(data.defects, confidenceThreshold, gradeFilter)
    : [];

  // 미디어 우선 그룹핑(#804) — 전체 media 목록에서 시작해 각 media의 하자를 붙인다.
  // 이렇게 하면 하자 0건 이미지도 갤러리에 노출된다 — 필터 조건과 무관하게 모든 촬영 이미지를 순회할 수 있다.
  // 수동 추가 하자(mediaId=null)는 이미지와 결부되지 않아 미디어 그룹 대상에서 제외한다.
  const mediaGroups = useMemo(() => {
    // 1. 필터된 하자(visibleDefects)를 mediaId별로 맵 구성
    const defectsByMediaId = new Map<number, typeof visibleDefects>();
    for (const defect of visibleDefects) {
      if (defect.mediaId == null) continue;
      if (!defectsByMediaId.has(defect.mediaId)) {
        defectsByMediaId.set(defect.mediaId, []);
      }
      defectsByMediaId.get(defect.mediaId)?.push(defect);
    }

    // 2. 전체 media 목록을 기준으로 그룹 생성(하자 없는 이미지도 포함)
    // id 오름차순 명시 정렬 — 백엔드 응답 순서에 기대지 않는다(#815, 이전/다음 이미지 네비게이션
    // 순서가 요청마다 흔들리지 않도록 고정).
    return (data?.media ?? [])
      .slice()
      .sort((a, b) => a.id - b.id)
      .map((media) => ({
        mediaId: media.id,
        imageUrl: media.imageUrl,
        thumbnailUrl: media.thumbnailUrl,
        defects: defectsByMediaId.get(media.id) ?? [], // 이 media의 필터된 하자 목록(없으면 빈 배열)
      }));
  }, [data?.media, visibleDefects]);

  // 현재 선택된 media(또는 첫 번째 media)
  const currentMediaGroup = useMemo(() => {
    if (mediaGroups.length === 0) return null;
    const currentId = selectedMediaId !== undefined ? selectedMediaId : (mediaGroups[0]?.mediaId ?? null);
    return mediaGroups.find((g) => g.mediaId === currentId) ?? mediaGroups[0] ?? null;
  }, [mediaGroups, selectedMediaId]);

  // 현재 media 그룹의 defects — 핸들러들이 "현재 보고 있는 이미지" 범위로 하자를 찾을 때 쓴다
  // (handleGenerateReport 등보다 먼저 선언 — 뒤 핸들러들이 전방참조 없이 곧장 쓸 수 있게).
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
    // currentDefects(현재 보고 있는 이미지)에서 찾는다 — 전체 하자 목록(visibleDefects)에서
    // 찾으면 다른 이미지를 보고 있어도 항상 첫 번째 이미지의 하자를 대상으로 삼는 오동작이
    // 있었다(#784, 다중 이미지 뷰어 도입 후 미반영된 버그).
    const selected = findSelectedDefect(data.defects, currentDefects, selectedDefectId);
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
  }, [data, currentDefects, selectedDefectId, isUpdating, refetch]);

  const handleOpenGradeEdit = useCallback(() => {
    if (!data) return;
    const selected = findSelectedDefect(data.defects, currentDefects, selectedDefectId);
    if (selected) {
      setGradeEditId(selected.id);
      setSelectedGrade(selected.grade);
    }
  }, [data, currentDefects, selectedDefectId]);

  const handleConfirmGrade = useCallback(async () => {
    if (!data) return;
    if (!gradeReason.trim() || gradeReason.trim().length > 500) {
      setErrorMessage('수정 사유는 1-500자 범위여야 합니다.');
      return;
    }
    const selected = findSelectedDefect(data.defects, currentDefects, selectedDefectId);
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
  }, [data, currentDefects, selectedDefectId, selectedGrade, gradeReason, isUpdating, refetch]);

  const handleCancelGradeEdit = useCallback(() => {
    setGradeEditId(undefined);
    setSelectedGrade('');
  }, []);

  const handleCreateMissingDefect = useCallback(async () => {
    if (!newDefectType || !newDefectGrade || isUpdating) return;
    setIsUpdating(true);
    setErrorMessage('');
    try {
      const payload = {
        type: newDefectType as 'CRACK' | 'SPALLING' | 'LEAK_EFFLORESCENCE' | 'REBAR_EXPOSURE' | 'PAINT_DAMAGE',
        grade: newDefectGrade as DefectGrade,
        ...(draggingBbox && {
          bboxX: draggingBbox.x,
          bboxY: draggingBbox.y,
          bboxW: draggingBbox.width,
          bboxH: draggingBbox.height,
        }),
        ...(currentMediaGroup?.mediaId && { mediaId: currentMediaGroup.mediaId }),
      };
      const response = await inspectionApi.createDefect(inspectionId, payload);
      await refetch();
      setSelectedDefectId(response.data.id);
      setIsAddMissingOpen(false);
      setIsDrawingMissing(false);
      setNewDefectType('');
      setNewDefectGrade('');
      setDraggingBbox(undefined);
      setCanvasMouseDown(false);
    } catch (error) {
      const msg = error instanceof Error ? error.message : '누락 추가에 실패했습니다.';
      setErrorMessage(msg);
    } finally {
      setIsUpdating(false);
    }
  }, [inspectionId, newDefectType, newDefectGrade, isUpdating, refetch, draggingBbox, currentMediaGroup?.mediaId]);

  const handleCancelAddMissing = useCallback(() => {
    if (isUpdating) return;
    setIsAddMissingOpen(false);
    setIsDrawingMissing(false);
    setNewDefectType('');
    setNewDefectGrade('');
    setDraggingBbox(undefined);
    setCanvasMouseDown(false);
    setErrorMessage('');
  }, [isUpdating]);

  // 누락 추가 버튼 클릭 — 모달을 바로 열지 않고 메인 뷰어 위 그리기 모드로 전환한다(#874, 2안).
  const handleStartDrawingMissing = useCallback(() => {
    setIsDrawingMissing(true);
    setDraggingBbox(undefined);
    setErrorMessage('');
  }, []);

  // 위치 지정 없이 바로 유형/등급 선택으로 진행(기존 "박스는 선택사항" 동작 유지).
  const handleSkipDrawingMissing = useCallback(() => {
    setDraggingBbox(undefined);
    setIsDrawingMissing(false);
    setIsAddMissingOpen(true);
  }, []);

  const handleCancelDrawingMissing = useCallback(() => {
    setIsDrawingMissing(false);
    setDraggingBbox(undefined);
    setCanvasMouseDown(false);
  }, []);

  // ponytail: 캔버스 드래그 이벤트 — 마우스 위치를 이미지 좌표계(0~1 정규화)로 변환.
  // 메인 뷰어(DefectOverlay)의 좌표계를 그대로 재사용 — 별도 축소 캔버스를 두지 않는다(#874).
  const handleCanvasMouseDown = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      const canvas = e.currentTarget;
      const rect = canvas.getBoundingClientRect();
      const x = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
      const y = Math.max(0, Math.min(1, (e.clientY - rect.top) / rect.height));
      setCanvasMouseDown(true);
      setDraggingBbox({ x, y, width: 0, height: 0 });
    },
    [],
  );

  const handleCanvasMouseMove = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      if (!canvasMouseDown || !draggingBbox) return;
      const canvas = e.currentTarget;
      const rect = canvas.getBoundingClientRect();
      const x = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
      const y = Math.max(0, Math.min(1, (e.clientY - rect.top) / rect.height));
      setDraggingBbox({
        x: Math.min(draggingBbox.x, x),
        y: Math.min(draggingBbox.y, y),
        width: Math.abs(x - draggingBbox.x),
        height: Math.abs(y - draggingBbox.y),
      });
    },
    [canvasMouseDown, draggingBbox],
  );

  // 드래그 없이 클릭만 하면 0크기 박스가 그대로 제출되던 것을 방지(#841) — 최소 임계값 미만이면
  // 위치 미지정(undefined)으로 되돌린다. 유효한 크기로 드래그가 끝나면 그리기 모드를 마치고
  // 유형/등급 선택 모달을 연다(#874).
  const handleCanvasMouseUp = useCallback(() => {
    setCanvasMouseDown(false);
    if (!draggingBbox) return;
    if (draggingBbox.width < MIN_BBOX_SIZE || draggingBbox.height < MIN_BBOX_SIZE) {
      setDraggingBbox(undefined);
      return;
    }
    setIsDrawingMissing(false);
    setIsAddMissingOpen(true);
  }, [draggingBbox]);

  const handleConfirmReview = useCallback(async () => {
    if (!data) return;
    const selected = findSelectedDefect(data.defects, currentDefects, selectedDefectId);
    if (!selected || isUpdating) return;
    setIsUpdating(true);
    setErrorMessage('');
    try {
      await inspectionApi.updateDefectStatus(selected.id, { status: 'CONFIRMED' });
      await refetch();
      // 이 이미지에 더 확정할 하자가 없으면 다음 이미지로 자동 이동(요청 반영, #784).
      // refetch()의 서버 응답을 기다리지 않고 방금 확정한 것 기준으로 낙관적으로 판단한다 —
      // currentDefects는 확정 이전 스냅샷이라 selected를 제외하고 계산해야 한다.
      const hasMoreToConfirm = currentDefects.some(
        (d) => d.id !== selected.id && d.status === 'DETECTED',
      );
      if (!hasMoreToConfirm) {
        handleNextMedia();
      }
    } catch (error) {
      const msg = error instanceof Error ? error.message : '검수 확정에 실패했습니다.';
      setErrorMessage(msg);
    } finally {
      setIsUpdating(false);
    }
  }, [data, currentDefects, selectedDefectId, isUpdating, refetch, handleNextMedia]);

  const handleGenerateReport = useCallback(() => {
    navigate(`/inspections/${inspectionId}/reports`);
  }, [inspectionId, navigate]);

  if (!Number.isInteger(inspectionId) || inspectionId <= 0) {
    return (
      <div className="p-5 text-red-600">잘못된 접근입니다. 유효한 검사 ID를 확인하세요.</div>
    );
  }

  if (isLoading) return <AILoadingIndicator message="점검 결과를 분석 중입니다..." />;
  if (isError) return <AIErrorFallback onRetry={() => void refetch()} />;
  if (!data || data.defects.length === 0)
    return <div className="p-5">탐지된 하자가 없습니다.</div>;

  const selected = findSelectedDefect(data.defects, currentDefects, selectedDefectId);

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
          disabled={data.reviewedCount !== data.totalCount}
          title={data.reviewedCount !== data.totalCount ? `${data.reviewedCount}/${data.totalCount} 하자 검수 확정 필요` : ''}
        >
          점검 요약
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
          {/* Left: Image Viewer Section — overflow-y-auto(#902): 부모 Unified Card가
              overflow-hidden이라, 이미지(max-h-[79vh])가 낮은 뷰포트에서 진행률바·검수확정
              버튼과 합쳐 이 컬럼 높이를 넘기면 클립돼 버튼이 영구히 안 보이게 된다. 넘칠 때
              스크롤로 항상 닿을 수 있게 방어(#897 79vh 상향의 후속 안전장치). */}
          <div className="flex flex-1 flex-col gap-6 overflow-y-auto bg-surface-sunken p-6">
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

            <div className="flex flex-1 flex-col items-center justify-center gap-2">
              {/* 누락 추가 그리기 모드 툴바 — 메인 이미지 위에서 직접 드래그(#874, 2안) */}
              {isDrawingMissing && (
                <div className="flex w-full items-center justify-between rounded-lg bg-primary/10 px-4 py-2 text-sm text-text-default">
                  <span>이미지 위에 드래그해서 하자 위치를 표시하세요.</span>
                  <div className="flex gap-2">
                    <Button type="button" variant="secondary" size="sm" onClick={handleSkipDrawingMissing}>
                      박스 없이 계속
                    </Button>
                    <Button type="button" variant="secondary" size="sm" onClick={handleCancelDrawingMissing}>
                      취소
                    </Button>
                  </div>
                </div>
              )}
              {currentMediaGroup ? (
                <>
                  <DefectOverlay
                    media={{
                      id: currentMediaGroup.mediaId,
                      imageUrl: currentMediaGroup.imageUrl ?? '',
                      thumbnailUrl: currentMediaGroup.thumbnailUrl,
                    }}
                    defects={currentDefects}
                    selectedId={selected?.id}
                    onSelect={setSelectedDefectId}
                    drawMode={isDrawingMissing}
                    draggingBbox={draggingBbox}
                    onCanvasMouseDown={handleCanvasMouseDown}
                    onCanvasMouseMove={handleCanvasMouseMove}
                    onCanvasMouseUp={handleCanvasMouseUp}
                  />
                  {currentDefects.length === 0 && (
                    <div className="text-sm text-text-muted">
                      {visibleDefects.length === 0 ? '조건에 맞는 하자가 없습니다.' : '이 이미지에 해당하는 하자가 없습니다.'}
                    </div>
                  )}
                </>
              ) : (
                <div className="text-sm text-text-muted">표시할 이미지가 없습니다.</div>
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

          {/* Right: Analysis Panel — currentMediaGroup만 있으면 항상 렌더(#874: 하자 0건이어도
              등급수정/누락추가 버튼이 통째로 사라지지 않도록). AI 분석 결과 섹션만 selected에 의존. */}
          {currentMediaGroup && (
            <div className="flex w-80 flex-col border-l border-border">
              <div className="px-5 py-5">
                <h3 className="font-medium text-text-default">AI 분석 결과</h3>
              </div>
              <div className="flex-1 overflow-y-auto px-5">
                {selected ? (
                  <>
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
                  </>
                ) : (
                  <div className="text-sm text-text-muted">선택된 하자가 없습니다.</div>
                )}
              </div>

              {/* Grade Edit Mode — 등급 수정 모달(#827) */}
              {!gradeEditId && (
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
                    onClick={handleStartDrawingMissing}
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

      {/* Grade Edit Modal (#827) */}
      <Modal
        open={gradeEditId !== undefined}
        onClose={handleCancelGradeEdit}
        title="등급 수정"
        closeOnOverlayClick={!isUpdating}
      >
        <div className="flex flex-col gap-4">
          <p className="text-xs text-text-muted">
            보정된 심각도 등급 선택수동 검토에 기반하여
          </p>
          {errorMessage && (
            <div className="rounded-lg bg-red-100 p-3 text-sm text-red-700">{errorMessage}</div>
          )}
          {/* 라디오 그룹 — 2열 grid (A,B / C,D / E) */}
          <div role="radiogroup" aria-label="등급 선택" className="grid grid-cols-2 gap-2">
            {ALL_GRADES.map((grade) => (
              <label
                key={grade}
                className={`flex cursor-pointer items-center justify-center gap-2 rounded-[20px] border-2 px-4 py-2.5 font-medium transition ${
                  selectedGrade === grade
                    ? 'border-black bg-black/5 text-text-default'
                    : 'border-[#e4e4e7] text-text-default hover:bg-surface-muted'
                }`}
              >
                <input
                  type="radio"
                  name="grade-select"
                  value={grade}
                  checked={selectedGrade === grade}
                  onChange={(e) => setSelectedGrade(e.target.value as DefectGrade)}
                  className="sr-only"
                />
                <span
                  className="h-2 w-2 shrink-0 rounded-full"
                  style={{ backgroundColor: GRADE_DOT_COLORS[grade] }}
                  aria-hidden="true"
                />
                {GRADE_LABELS[grade]}
              </label>
            ))}
          </div>
          {/* 수정 사유 textarea */}
          <div>
            <label htmlFor="grade-reason-textarea" className="mb-2 block text-sm font-medium text-text-default">
              수정 사유
            </label>
            <textarea
              id="grade-reason-textarea"
              value={gradeReason}
              onChange={(e) => setGradeReason(e.target.value)}
              placeholder="수정 사유를 입력해주세요 (1-500자)"
              maxLength={500}
              className="w-full rounded-lg border border-border bg-white px-3 py-2 text-sm"
              rows={3}
            />
          </div>
          {/* 모달 버튼 */}
          <div className="flex gap-2 pt-2">
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
            <Button
              type="button"
              variant="primary"
              size="lg"
              className="flex-1"
              onClick={handleConfirmGrade}
              disabled={!selectedGrade || !gradeReason.trim() || isUpdating}
            >
              확인
            </Button>
          </div>
        </div>
      </Modal>

      {/* Add Missing Defect Modal */}
      <Modal
        open={isAddMissingOpen}
        onClose={handleCancelAddMissing}
        title="누락된 하자 추가"
        closeOnOverlayClick={!isUpdating}
      >
        <div className="flex max-h-96 flex-col gap-4 overflow-y-auto">
          {/* 박스 위치는 모달을 열기 전 메인 뷰어 이미지 위에서 이미 지정됐다(#874, 2안) —
              여기서는 별도 축소 캔버스를 다시 그리지 않고 결과만 보여준다. */}
          <p className="text-xs text-text-muted">
            {draggingBbox
              ? '이미지 위에 하자 위치가 지정되었습니다.'
              : '하자 위치가 지정되지 않았습니다. (전체 이미지 기준으로 추가됩니다)'}
          </p>
          {errorMessage && (
            <div className="rounded-lg bg-red-100 p-3 text-sm text-red-700">{errorMessage}</div>
          )}

          <div>
            <label htmlFor="defect-type-select" className="mb-2 block text-sm font-medium text-text-default">
              하자 유형
            </label>
            <select
              id="defect-type-select"
              value={newDefectType}
              onChange={(e) =>
                setNewDefectType(
                  e.target.value as 'CRACK' | 'SPALLING' | 'LEAK_EFFLORESCENCE' | 'REBAR_EXPOSURE' | 'PAINT_DAMAGE' | '',
                )
              }
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
            <label htmlFor="defect-grade-select" className="mb-2 block text-sm font-medium text-text-default">
              등급
            </label>
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
