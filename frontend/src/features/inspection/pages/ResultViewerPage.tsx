import { useState, useCallback, useEffect, useMemo, useRef } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { AIErrorFallback } from '../../../shared/components/AIErrorFallback';
import { AILoadingIndicator } from '../../../shared/components/AILoadingIndicator';
import { Button } from '../../../shared/components/Button';
import { getApiErrorMessage } from '../../../shared/api/types';
import { Modal } from '../../../shared/components/Modal/Modal';
import { DefectOverlay } from '../components/DefectOverlay';
import { DeletedDefectsPanel } from '../components/DeletedDefectsPanel';
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

// 되살리기 사유 기본값(#1399) — 서버가 1~500자를 필수로 요구한다. 수정 가능하다.
const RESTORE_REASON_DEFAULT = '오탐 판정 취소';

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

  // 신뢰도 슬라이더는 제거됐다(팀 QA 요청 — 등급 버튼만으로 충분한데 조작이 번거로움). filterDefects
  // 호출부에서 confidence 인자를 0(전체 노출)으로 고정한다 — 안 보이는 사각지대를 만들지 않는다는
  // 취지(이전 커밋 사유)는 그대로 유지.
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
  // 오탐 삭제 사유 입력 — 브라우저 prompt()가 아니라 등급 수정·누락 추가와 같은 모달로 받는다(#1255).
  const [isDeleteOpen, setIsDeleteOpen] = useState(false);
  const [deleteReason, setDeleteReason] = useState('');
  // 오탐 되살리기(#1399) — 서버가 사유를 필수로 받으므로(감사 이력) 삭제와 같은 모달로 확인받는다.
  // 기본 문구를 채워 두어 "실수를 되돌리는" 흔한 경우엔 한 번만 누르면 되게 한다.
  const [restoreTargetId, setRestoreTargetId] = useState<number | undefined>();
  const [restoreReason, setRestoreReason] = useState(RESTORE_REASON_DEFAULT);
  const [isAddMissingOpen, setIsAddMissingOpen] = useState(false);
  // 누락 추가 그리기 모드 — 메인 뷰어 이미지 위에서 직접 드래그로 박스를 지정한다(#874, 2안).
  const [isDrawingMissing, setIsDrawingMissing] = useState(false);
  const [newDefectType, setNewDefectType] = useState<'CRACK' | 'SPALLING' | 'LEAK_EFFLORESCENCE' | 'REBAR_EXPOSURE' | 'PAINT_DAMAGE' | ''>('');
  const [newDefectGrade, setNewDefectGrade] = useState<DefectGrade | ''>('');
  // ponytail: 캔버스 드래그 상태 — 그리기 모드 진입/모달 닫힘마다 리셋
  const [draggingBbox, setDraggingBbox] = useState<{ x: number; y: number; width: number; height: number } | undefined>();
  const [canvasMouseDown, setCanvasMouseDown] = useState(false);
  // 드래그 시작점(마우스다운 위치) 고정 — mousemove가 draggingBbox.x/y(매 프레임 min()으로 갱신되는
  // 값)를 기준으로 폭을 계산하면, 오른쪽에서 왼쪽으로 끄는 드래그에서 그 min()이 매번 "현재" 값으로
  // 덮어써져 원래 시작점을 잃는다 — 왼쪽→오른쪽만 정상이고 반대 방향은 매 프레임 델타만큼만
  // 커지는 버그였다(팀 QA 발견). 시작점을 별도 ref로 고정해 두 방향 모두 항상 그 고정점 기준으로
  // 계산한다.
  const drawStartRef = useRef<{ x: number; y: number } | undefined>();
  // 드래그 중 이미지 사각형(마우스다운 시점에 한 번만 캡처) — window 레벨 리스너는 e.currentTarget이
  // 이미지 div가 아니라 window 자체라 별도로 기준 사각형을 들고 있어야 한다.
  const canvasRectRef = useRef<DOMRect | undefined>();
  // 드래그 중 커서가 이미지 밖으로 나갔음을 안내하는 배너 표시 여부(팀 QA 요청) — 그냥 조용히
  // 중단되면 사용자는 왜 박스 그리기가 멈췄는지 알 수 없다.
  const [isDrawOutOfBounds, setIsDrawOutOfBounds] = useState(false);
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
    ? filterDefects(data.defects, 0, gradeFilter)
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

  // 삭제된 하자도 "지금 보고 있는 이미지" 것만 보여준다 — 다른 사진에서 지운 것까지 섞이면
  // 무엇을 되살리는지 판단할 근거(이미지)가 화면에 없다(#1399).
  //
  // ⚠️ 의도된 제약(#1401): currentMediaGroup.mediaId 는 항상 실제 이미지 id 라, mediaId=null 인
  // 삭제 하자는 어떤 이미지에서도 이 목록에 뜨지 않는다. 서버는 반환하지만 화면에서 빠진다.
  // 그런 하자는 애초에 뷰어의 일반 목록·오버레이 어디에도 나타나지 않아 선택 자체가 불가능하고,
  // 따라서 뷰어로는 오탐 삭제도 할 수 없다 — 만들려면 API를 직접 두 번(생성·삭제) 쳐야 한다.
  // 도달 경로가 없는 케이스를 위해 별도 그룹을 만들지 않는다. 이 화면이 이미지 중심인 한 유지된다.
  const currentDeletedDefects = useMemo(
    () => (data?.deletedDefects ?? []).filter((item) => item.defect.mediaId === currentMediaGroup?.mediaId),
    [data?.deletedDefects, currentMediaGroup?.mediaId],
  );

  // 현재 media 인디케이터 (예: "이미지 1/2")
  const currentMediaIndex = mediaGroups.findIndex((g) => g.mediaId === currentMediaGroup?.mediaId);
  const mediaIndicator = mediaGroups.length > 0 ? `이미지 ${currentMediaIndex + 1}/${mediaGroups.length}` : '';
  const isLastMedia = currentMediaIndex === mediaGroups.length - 1;

  // 이전/다음 이미지를 미리 받아둔다(팀 QA 요청) — DefectOverlay의 imageUrl은 상세(고해상도)
  // 이미지라 로딩이 오래 걸리는데, "다음 이미지" 클릭 시점에야 요청이 시작되면 바운딩 박스(즉시
  // 렌더)와 사진(네트워크 대기)이 따로따로 나타난다. 인접 이미지만 미리 fetch해 브라우저 캐시에
  // 태워두면 클릭 시점엔 이미 캐시에서 그려진다 — 전체 이미지를 다 미리 받으면(수십 장) 지금 보는
  // 이미지 로딩과 대역폭을 다투므로 범위를 제한한다. new Image()는 DOM에 붙이지 않는 순수 프리페치
  // 트릭.
  //
  // 앞으로 2장 / 뒤로 1장(팀 QA 발견 — "다음" 연타 시 못 따라옴) — "다음" 연타가 "이전" 연타보다
  // 훨씬 흔한 사용 패턴이라 앞쪽에 더 넓은 버퍼를 둔다. 그래도 순간적으로 아주 빠르게 여러 장을
  // 넘기면(버퍼보다 빠른 클릭) 결국 못 따라잡는다 — 이건 실제 네트워크 전송 시간의 한계라 프리페치
  // 범위를 늘리는 것만으로 완전히 없앨 순 없다(DefectOverlay의 로딩 스피너가 그 남은 구간을
  // 가려준다).
  useEffect(() => {
    const neighbors = [
      mediaGroups[currentMediaIndex - 1],
      mediaGroups[currentMediaIndex + 1],
      mediaGroups[currentMediaIndex + 2],
    ];
    for (const neighbor of neighbors) {
      if (neighbor?.imageUrl) {
        new Image().src = neighbor.imageUrl;
      }
    }
  }, [mediaGroups, currentMediaIndex]);

  // 이 이미지의 검수 완료 여부 — 신뢰도·등급 필터와 무관하게 원본(data.defects) 기준으로 센다.
  // 필터로 가려진 하자를 "완료"로 오인하면 마지막에 점검 요약이 열리지 않아 사용자가 갇힌다.
  const currentMediaCounts = useMemo(() => {
    const mediaId = currentMediaGroup?.mediaId;
    const own = (data?.defects ?? []).filter((d) => d.mediaId === mediaId);
    return { total: own.length, pending: own.filter((d) => d.status === 'DETECTED').length };
  }, [data?.defects, currentMediaGroup?.mediaId]);

  // 하자가 있고 전부 확정된 이미지 = 검수 완료(하자 0건 이미지는 완료로 보지 않는다 — 누락 추가 여지를 남김).
  const isCurrentMediaReviewed = currentMediaCounts.total > 0 && currentMediaCounts.pending === 0;
  // 점검 요약 버튼의 활성 조건과 같은 식을 쓴다 — 안내 문구와 버튼 상태가 어긋나지 않도록.
  const allReviewed = !!data && data.totalCount > 0 && data.reviewedCount === data.totalCount;

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

  const handleOpenDeleteFalsePositive = useCallback(() => {
    setDeleteReason('');
    setErrorMessage('');
    setIsDeleteOpen(true);
  }, []);

  const handleCancelDeleteFalsePositive = useCallback(() => {
    if (isUpdating) return;
    setIsDeleteOpen(false);
    setDeleteReason('');
    setErrorMessage('');
  }, [isUpdating]);

  const handleConfirmDeleteFalsePositive = useCallback(async () => {
    if (!data) return;
    const reason = deleteReason.trim();
    if (reason.length === 0 || reason.length > 500) {
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
      await inspectionApi.reviewDefect(selected.id, { isDeleted: true, reason });
      await refetch();
      setIsDeleteOpen(false);
      setDeleteReason('');
    } catch (error) {
      const msg = error instanceof Error ? error.message : '오탐 삭제에 실패했습니다.';
      setErrorMessage(msg);
    } finally {
      setIsUpdating(false);
    }
  }, [data, deleteReason, currentDefects, selectedDefectId, isUpdating, refetch]);

  const handleOpenRestore = useCallback((defectId: number) => {
    setRestoreTargetId(defectId);
    setRestoreReason(RESTORE_REASON_DEFAULT);
    setErrorMessage('');
  }, []);

  const handleCancelRestore = useCallback(() => {
    if (isUpdating) return;
    setRestoreTargetId(undefined);
    setErrorMessage('');
  }, [isUpdating]);

  const handleConfirmRestore = useCallback(async () => {
    const reason = restoreReason.trim();
    if (restoreTargetId == null || isUpdating) return;
    if (reason.length === 0 || reason.length > 500) {
      setErrorMessage('사유는 1-500자 범위여야 합니다.');
      return;
    }
    setIsUpdating(true);
    setErrorMessage('');
    try {
      await inspectionApi.reviewDefect(restoreTargetId, { isDeleted: false, reason });
      await refetch();
      // 되살린 하자를 바로 선택해 준다 — 되돌린 뒤 이어서 검수하는 흐름이 자연스럽다.
      setSelectedDefectId(restoreTargetId);
      setRestoreTargetId(undefined);
    } catch (error) {
      const msg = error instanceof Error ? error.message : '되살리기에 실패했습니다.';
      setErrorMessage(msg);
    } finally {
      setIsUpdating(false);
    }
  }, [restoreTargetId, restoreReason, isUpdating, refetch]);

  const handleOpenGradeEdit = useCallback(() => {
    if (!data) return;
    const selected = findSelectedDefect(data.defects, currentDefects, selectedDefectId);
    if (selected) {
      setGradeEditId(selected.id);
      // 등급 미판정(null)이면 라디오를 비워 둔 채로 연다 — 검수자가 처음으로 등급을 매기는 경로.
      setSelectedGrade(selected.grade ?? '');
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
  // 이미 그리기 모드인 상태에서 다시 누르면 취소로 토글한다(팀 QA 발견 — 예전엔 항상 무조건
  // 그리기 모드를 (재)시작해서, 켜져 있는 상태에서 또 눌러도 끌 방법이 이 버튼 자체엔 없었다).
  const handleStartDrawingMissing = useCallback(() => {
    setIsDrawingMissing((prev) => {
      if (prev) {
        setDraggingBbox(undefined);
        setCanvasMouseDown(false);
        setIsDrawOutOfBounds(false);
        return false;
      }
      setDraggingBbox(undefined);
      setErrorMessage('');
      setIsDrawOutOfBounds(false);
      return true;
    });
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
    setIsDrawOutOfBounds(false);
    drawStartRef.current = undefined;
    canvasRectRef.current = undefined;
  }, []);

  // ponytail: 캔버스 드래그 이벤트 — 마우스 위치를 이미지 좌표계(0~1 정규화)로 변환.
  // 메인 뷰어(DefectOverlay)의 좌표계를 그대로 재사용 — 별도 축소 캔버스를 두지 않는다(#874).
  const handleCanvasMouseDown = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      const rect = e.currentTarget.getBoundingClientRect();
      const x = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
      const y = Math.max(0, Math.min(1, (e.clientY - rect.top) / rect.height));
      canvasRectRef.current = rect;
      drawStartRef.current = { x, y };
      setCanvasMouseDown(true);
      setDraggingBbox({ x, y, width: 0, height: 0 });
      setIsDrawOutOfBounds(false);
    },
    [],
  );

  // 드래그 중엔 이동/종료를 div가 아니라 window에서 듣는다(팀 QA 요청) — div의 onMouseMove/
  // onMouseUp/onMouseLeave만 쓰면 커서가 이미지 경계를 넘는 순간 이벤트가 끊겨(mouseleave) 드래그가
  // 가차없이 취소됐다. window 리스너는 커서가 어디에 있든 계속 좌표를 받아 [0,1]로 클램프하므로
  // 박스가 이미지 가장자리에 붙은 채 계속 진행되고, 실제로 마우스 버튼을 뗀 순간(mouseup, 어디서
  // 떼든 window가 받음)에만 종료된다 — "살짝 벗어나면 봐주고" 요구를 사실상 무제한 허용으로 만족.
  useEffect(() => {
    if (!canvasMouseDown) return;

    const handleWindowMouseMove = (e: MouseEvent) => {
      const rect = canvasRectRef.current;
      const start = drawStartRef.current;
      if (!rect || !start) return;
      const rawX = (e.clientX - rect.left) / rect.width;
      const rawY = (e.clientY - rect.top) / rect.height;
      setIsDrawOutOfBounds(rawX < 0 || rawX > 1 || rawY < 0 || rawY > 1);
      const x = Math.max(0, Math.min(1, rawX));
      const y = Math.max(0, Math.min(1, rawY));
      // 항상 고정된 시작점(start) 기준으로 계산 — 진행 중인 draggingBbox 값을 기준으로 삼지 않는다.
      setDraggingBbox({
        x: Math.min(start.x, x),
        y: Math.min(start.y, y),
        width: Math.abs(x - start.x),
        height: Math.abs(y - start.y),
      });
    };

    // 드래그 없이 클릭만 하면 0크기 박스가 그대로 제출되던 것을 방지(#841) — 최소 임계값 미만이면
    // 위치 미지정(undefined)으로 되돌린다. 유효한 크기로 드래그가 끝나면 그리기 모드를 마치고
    // 유형/등급 선택 모달을 연다(#874). mouseup 이벤트 자체의 좌표로 최종 박스를 다시 계산한다 —
    // 중간 draggingBbox state를 참조하면(클로저) 이 리스너가 구독된 시점의 값에 갇힌다.
    const handleWindowMouseUp = (e: MouseEvent) => {
      const rect = canvasRectRef.current;
      const start = drawStartRef.current;
      setCanvasMouseDown(false);
      setIsDrawOutOfBounds(false);
      drawStartRef.current = undefined;
      canvasRectRef.current = undefined;
      if (!rect || !start) {
        setDraggingBbox(undefined);
        return;
      }
      const x = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
      const y = Math.max(0, Math.min(1, (e.clientY - rect.top) / rect.height));
      const finalBox = {
        x: Math.min(start.x, x),
        y: Math.min(start.y, y),
        width: Math.abs(x - start.x),
        height: Math.abs(y - start.y),
      };
      if (finalBox.width < MIN_BBOX_SIZE || finalBox.height < MIN_BBOX_SIZE) {
        setDraggingBbox(undefined);
        return;
      }
      setDraggingBbox(finalBox);
      setIsDrawingMissing(false);
      setIsAddMissingOpen(true);
    };

    window.addEventListener('mousemove', handleWindowMouseMove);
    window.addEventListener('mouseup', handleWindowMouseUp);
    return () => {
      window.removeEventListener('mousemove', handleWindowMouseMove);
      window.removeEventListener('mouseup', handleWindowMouseUp);
    };
  }, [canvasMouseDown]);

  const handleConfirmReview = useCallback(async () => {
    if (!data) return;
    const selected = findSelectedDefect(data.defects, currentDefects, selectedDefectId);
    if (!selected || isUpdating) return;
    // 확정 후 다음 미확정 하자로 자동 이동한다(팀 QA 요청, #1255의 "자동 이동 안 함" 결정을
    // 뒤집음). #1255의 우려는 "뭐가 확정됐는지 화면에서 안 보임"이었는데, 확정된 박스가 이제
    // 초록으로 남아있어(DefectOverlay) 화면이 바뀌어도 방금 확정한 게 무엇인지 계속 보인다 — 그
    // 우려가 해소돼 이동을 막을 이유가 없다. 다음 대상은 확정 API 호출 전(현재 목록) 기준으로
    // 미리 고른다 — refetch 완료를 기다려 새 데이터로 계산할 필요 없이 즉시 이동할 수 있다.
    //
    // "배열상 다음 것"이 아니라 "남은 미확정 중 첫 번째"로 고른다(팀 QA 발견 회귀) — 사용자가
    // 순서 없이 아무 박스나 골라 확정할 수 있어서, 방금 확정한 게 목록 맨 뒤(index+1이 없음)면
    // 앞쪽에 미확정이 남아 있어도 자동 이동이 전혀 안 됐다.
    const remainingPending = currentDefects.filter(
      (d) => d.status === 'DETECTED' && d.id !== selected.id,
    );
    const next = remainingPending[0];
    setIsUpdating(true);
    setErrorMessage('');
    try {
      await inspectionApi.updateDefectStatus(selected.id, { status: 'CONFIRMED' });
      await refetch();
      if (next) {
        setSelectedDefectId(next.id);
      }
    } catch (error) {
      const msg = error instanceof Error ? error.message : '검수 확정에 실패했습니다.';
      setErrorMessage(msg);
    } finally {
      setIsUpdating(false);
    }
  }, [data, currentDefects, selectedDefectId, isUpdating, refetch]);

  // "점검 요약" 클릭 시 회차 검수를 먼저 확정(ANALYZED→REVIEWED)한 뒤 이동한다 — 이전엔 이 전이가
  // 최종 보고서 확정에만 묶여 있어, 검수는 끝났는데 보고서를 안(못) 만든 회차가 계속 "진행 중"으로
  // 잡혀 같은 시설물의 새 회차 생성마다 중복 경고가 떴다. 서버가 멱등 처리하므로 재진입·중복
  // 클릭은 안전하다.
  const handleGenerateReport = useCallback(async () => {
    if (isUpdating) return;
    setIsUpdating(true);
    setErrorMessage('');
    try {
      await inspectionApi.confirmReview(inspectionId);
    } catch (error) {
      // 확정 실패해도 이동은 막지 않는다(PR머신 리뷰 P2) — 보고서 화면 자체(generateDraft)는
      // 회차 상태와 무관하게 동작하도록 설계돼 있어(ReportService 주석), 검수 확정 실패를
      // 진입의 하드 블로커로 두면 "검수 다 끝낸 회차인데 서버 확정만 실패해서 보고서 화면에
      // 영영 못 들어감"이라는 막다른 길이 생긴다. 서버는 REVIEWED/REPORTED에 멱등이라 다음
      // 재진입 때 자연히 다시 시도된다 — 여기서는 경고만 남기고 그대로 진행한다.
      console.warn('회차 검수 확정 실패 — 그대로 보고서 화면으로 진행', getApiErrorMessage(error, ''));
    } finally {
      setIsUpdating(false);
    }
    navigate(`/inspections/${inspectionId}/reports`);
  }, [inspectionId, navigate, isUpdating]);

  if (!Number.isInteger(inspectionId) || inspectionId <= 0) {
    return (
      <div className="p-5 text-red-600">잘못된 접근입니다. 유효한 검사 ID를 확인하세요.</div>
    );
  }

  if (isLoading) return <AILoadingIndicator message="점검 결과를 분석 중입니다..." />;
  if (isError) return <AIErrorFallback onRetry={() => void refetch()} />;
  if (!data) return <div className="p-5">탐지된 하자가 없습니다.</div>;

  const selected = findSelectedDefect(data.defects, currentDefects, selectedDefectId);

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
          onClick={() => void handleGenerateReport()}
          disabled={data.reviewedCount !== data.totalCount || isUpdating}
          title={data.reviewedCount !== data.totalCount ? `${data.reviewedCount}/${data.totalCount} 하자 검수 확정 필요` : ''}
        >
          {isUpdating ? '이동 중...' : '점검 요약'}
        </Button>
      </div>

      {/* Filter Controls — Top Level (신뢰도 슬라이더 제거, 팀 QA 요청 — 등급 버튼만으로 충분) */}
      <div className="flex items-center gap-4">
        {/* 등급(A~E) 자체는 점검자라면 이미 안다(팀 QA 정정) — 모르는 건 "이 버튼이 뭘 하는지"
            (등급별 필터 토글이라는 것)다. 캡션·title 모두 필터 동작을 설명하도록 바꾼다. */}
        <span className="text-xs text-text-muted">등급 필터 (선택한 등급만 표시):</span>
        <div className="flex gap-2">
          {ALL_GRADES.map((grade) => (
            <label
              key={grade}
              title={`${GRADE_LABELS[grade]}만 표시 — 다시 누르면 숨김`}
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
                  <span className={isDrawOutOfBounds ? 'font-medium text-danger' : ''}>
                    {isDrawOutOfBounds
                      ? '이미지 범위를 벗어났습니다. 이미지 안에서 다시 드래그해 주세요.'
                      : '이미지 위에 드래그해서 하자 위치를 표시하세요.'}
                  </span>
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
                  />
                  {/* 이 이미지에서 찾은 하자 수 대비 검수 확정 수(팀 QA 요청) — 신뢰도·등급
                      필터와 무관하게 원본 기준(currentMediaCounts)이라 오탐 삭제·누락 추가로
                      실제 개수가 바뀌면(refetch 후) 그대로 반영된다. 범례(DefectOverlay 안)
                      바로 아래 둬서 같은 정보 묶음으로 보이게 한다. */}
                  {currentMediaCounts.total > 0 && (
                    <span className="text-[11px] text-text-muted">
                      이 이미지에서 찾은 하자 — 검수{' '}
                      {currentMediaCounts.total - currentMediaCounts.pending} / {currentMediaCounts.total}
                    </span>
                  )}
                  {currentDefects.length === 0 && (
                    <div className="text-sm text-text-muted">
                      {data.defects.length === 0
                        ? '탐지된 하자가 없습니다.'
                        : visibleDefects.length === 0
                          ? '조건에 맞는 하자가 없습니다.'
                          : isLastMedia
                            ? '이 이미지의 하자가 없습니다.'
                            : // 검수할 게 없는 이미지에서 다음 행동을 명시한다 — 마지막 이미지에서는
                              // '다음 이미지' 버튼이 비활성이라 안내하지 않는다(이미지가 1장뿐일 때도 동일).
                              "이 이미지의 하자가 없습니다. 상단의 '다음 이미지' 버튼으로 이동하세요."}
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

            {/* 검수 완료 안내 — 확정이 끝난 뒤 "다음에 뭘 눌러야 하는지"를 문구로 알린다(#1255).
                CTA 버튼은 두지 않는다 — 가리키는 버튼(상단 '다음 이미지', 헤더 '점검 요약')이
                이미 화면에 있고 그때 활성화되므로, 같은 동작을 하는 버튼이 둘이 된다. */}
            {(isCurrentMediaReviewed || allReviewed) && (
              <div className="rounded-lg bg-primary/10 px-4 py-3 text-sm text-text-default">
                {allReviewed
                  ? "모든 하자의 검수가 완료되었습니다. 우측 상단 '점검 요약' 버튼으로 이동하세요."
                  : isLastMedia
                    ? `이 이미지의 검수가 완료되었습니다. 아직 확정되지 않은 하자가 ${data.totalCount - data.reviewedCount}건 남아 있습니다.`
                    : "이 이미지의 검수가 완료되었습니다. 상단의 '다음 이미지' 버튼으로 이동하세요."}
              </div>
            )}

            {/* Action Buttons — 우측 패널의 등급수정/누락추가와 동일 높이로 하단 정렬.
                확정이 끝난 하자(status !== 'DETECTED')에는 오탐 삭제·검수 확정 모두 잠근다(#1255). */}
            {visibleDefects.length > 0 && (
              <div className="flex flex-col gap-2">
                {errorMessage && (
                  <div className="rounded-lg bg-red-100 p-3 text-sm text-red-700">{errorMessage}</div>
                )}
                {/* 비활성 버튼만 두면 왜 안 눌리는지 알 수 없다 — 다음 행동을 문구로 명시(#1397). */}
                {selected && selected.status === 'DETECTED' && selected.grade == null && (
                  <div className="rounded-lg bg-warning-soft-bg px-4 py-3 text-sm text-warning-soft-fg">
                    등급이 지정되지 않은 하자입니다. 우측 &apos;등급 수정&apos;으로 등급을 먼저 정해야 검수 확정할 수 있습니다.
                  </div>
                )}
                <div className="flex items-center gap-3">
                <Button
                  type="button"
                  variant="danger-soft"
                  size="lg"
                  className="flex-[3]"
                  onClick={handleOpenDeleteFalsePositive}
                  disabled={isUpdating || !selected || selected.status !== 'DETECTED'}
                >
                  오탐 삭제
                </Button>
                {/* 등급 미판정 하자는 확정을 막는다(#1397) — 확정되면 status가 DETECTED를 벗어나
                    '등급 수정' 버튼까지 잠겨(아래 :721) 앱 어디서도 등급을 부여할 수 없는 영구
                    미분류로 고착된다. 백엔드도 같은 규칙을 갖지만(Defect.changeStatus) 화면에서
                    먼저 막아 무엇을 해야 하는지(등급 수정 먼저) 알린다. */}
                <Button
                  type="button"
                  variant="primary"
                  size="lg"
                  className="flex-[7]"
                  onClick={handleConfirmReview}
                  disabled={
                    isUpdating ||
                    !selected ||
                    selected.status !== 'DETECTED' ||
                    selected.grade == null ||
                    // 등급수정이 이미 reviewed=true를 세웠다면(#1643 1.1안) 이 버튼으로 다시
                    // 확정할 필요가 없다 — 등급수정만으로 검수 카운트가 오르는데 이 버튼까지
                    // 별도로 눌리는 것을 버그로 오인하는 문제를 막는다.
                    selected.isReviewed
                  }
                  title={
                    selected && selected.status === 'DETECTED' && selected.grade == null
                      ? '등급이 없는 하자입니다. 먼저 우측 «등급 수정»으로 등급을 지정하세요.'
                      : selected && selected.status === 'DETECTED' && selected.isReviewed
                        ? '등급 수정으로 이미 검수가 완료된 하자입니다.'
                        : ''
                  }
                >
                  이 하자 검수 확정
                </Button>
                </div>
              </div>
            )}

            {/* 오탐 삭제 되살리기(#1399) — 액션 버튼 바로 아래. 기본 접힘이라 평소엔 한 줄만 차지한다.
                visibleDefects 조건 밖에 둔다: 이 이미지의 하자를 전부 오탐 삭제해 화면이 비어도
                되돌아올 자리는 남아 있어야 한다. */}
            <DeletedDefectsPanel
              items={currentDeletedDefects}
              onRestore={handleOpenRestore}
              restoringId={restoreTargetId}
              disabled={isUpdating}
            />
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
                          <div className="mb-2 text-xs text-text-muted">예상 폭</div>
                          <div className="text-xl font-bold text-text-default">{selected.widthMm}mm</div>
                        </div>
                      ) : (
                        <div className="flex-1 rounded-[12px] border border-border bg-surface-muted p-4">
                          {/* "면적 비율"은 실측 면적으로 오해될 수 있어 정직화(#1643) — 실제로는
                              마스크 면적/사진(이미지) 면적의 비율이지 실측 면적이 아니다. 표시값
                              계산(areaRatio)은 변경하지 않는다. */}
                          <div className="mb-2 text-xs text-text-muted">사진 내 비율</div>
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
                      {/* 등급 미판정 하자는 AI 설명을 요청하지 않는다 — 프롬프트 입력이 등급이라
                          null을 넘기면 근거 없는 설명이 나온다. 먼저 '등급 수정'으로 등급을 매기면 뜬다. */}
                      {data && selected.grade != null ? (
                        <InspectionDefectExplainPanel
                          defectType={selected.type}
                          grade={selected.grade}
                          facilityType={data.facilityType}
                        />
                      ) : (
                        <div className="text-sm text-text-muted">
                          등급이 아직 매겨지지 않은 하자입니다. &apos;등급 수정&apos;으로 등급을 지정하면 AI 분석이 표시됩니다.
                        </div>
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
                    disabled={isUpdating || !selected || selected.status !== 'DETECTED'}
                  >
                    등급 수정
                  </Button>
                  {/* 그리기 모드 중엔 primary로 눌린 상태를 표시한다(팀 QA 요청) — secondary
                      그대로 두면 지금 이 버튼이 켜져 있는지(캔버스가 그리기 대기 중인지) 표시가
                      없었다. */}
                  <Button
                    type="button"
                    variant={isDrawingMissing ? 'primary' : 'secondary'}
                    size="lg"
                    className="flex-1"
                    onClick={handleStartDrawingMissing}
                    disabled={isUpdating || isCurrentMediaReviewed || allReviewed}
                    aria-pressed={isDrawingMissing}
                  >
                    {isDrawingMissing ? '그리기 중' : '누락 추가'}
                  </Button>
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* False Positive Delete Modal (#1255) — 브라우저 prompt() 대신 등급 수정·누락 추가와 같은 모달 */}
      <Modal
        open={isDeleteOpen}
        onClose={handleCancelDeleteFalsePositive}
        title="오탐 삭제"
        closeOnOverlayClick={!isUpdating}
      >
        <div className="flex flex-col gap-4">
          <p className="text-xs text-text-muted">
            AI가 잘못 탐지한 하자로 판단한 이유를 남겨주세요. 삭제된 하자는 보고서에 포함되지 않습니다.
          </p>
          {errorMessage && (
            <div className="rounded-lg bg-red-100 p-3 text-sm text-red-700">{errorMessage}</div>
          )}
          <div>
            <label htmlFor="delete-reason-textarea" className="mb-2 block text-sm font-medium text-text-default">
              삭제 사유
            </label>
            <textarea
              id="delete-reason-textarea"
              value={deleteReason}
              onChange={(e) => setDeleteReason(e.target.value)}
              // Enter로 바로 삭제 확정(팀 QA 요청) — Shift+Enter는 그대로 줄바꿈으로 남겨둔다
              // (여러 줄 사유를 쓰는 사람도 있어서). 삭제 버튼의 disabled 조건과 동일하게 검증한다.
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  if (deleteReason.trim() && !isUpdating) {
                    void handleConfirmDeleteFalsePositive();
                  }
                }
              }}
              placeholder="삭제 사유를 입력해주세요 (1-500자)"
              maxLength={500}
              className="w-full rounded-lg border border-border bg-white px-3 py-2 text-sm"
              rows={3}
            />
          </div>
          <div className="flex gap-2 pt-2">
            <Button
              type="button"
              variant="secondary"
              size="lg"
              className="flex-1"
              onClick={handleCancelDeleteFalsePositive}
              disabled={isUpdating}
            >
              취소
            </Button>
            <Button
              type="button"
              variant="danger-soft"
              size="lg"
              className="flex-1"
              onClick={handleConfirmDeleteFalsePositive}
              disabled={!deleteReason.trim() || isUpdating}
            >
              삭제
            </Button>
          </div>
        </div>
      </Modal>

      {/* Restore Modal (#1399) — 삭제와 같은 형식으로 사유를 받는다(감사 이력 append-only) */}
      <Modal
        open={restoreTargetId !== undefined}
        onClose={handleCancelRestore}
        title="삭제한 하자 되살리기"
        closeOnOverlayClick={!isUpdating}
      >
        <div className="flex flex-col gap-4">
          <p className="text-xs text-text-muted">
            삭제를 되돌립니다. 되살린 하자는 다시 검수 대상이 되고 보고서에도 포함됩니다.
          </p>
          {errorMessage && (
            <div className="rounded-lg bg-red-100 p-3 text-sm text-red-700">{errorMessage}</div>
          )}
          <div>
            <label htmlFor="restore-reason-textarea" className="mb-2 block text-sm font-medium text-text-default">
              되살리는 사유
            </label>
            <textarea
              id="restore-reason-textarea"
              value={restoreReason}
              onChange={(e) => setRestoreReason(e.target.value)}
              placeholder="되살리는 사유를 입력해주세요 (1-500자)"
              maxLength={500}
              className="w-full rounded-lg border border-border bg-white px-3 py-2 text-sm"
              rows={3}
            />
          </div>
          <div className="flex gap-2 pt-2">
            <Button
              type="button"
              variant="secondary"
              size="lg"
              className="flex-1"
              onClick={handleCancelRestore}
              disabled={isUpdating}
            >
              취소
            </Button>
            <Button
              type="button"
              variant="primary"
              size="lg"
              className="flex-1"
              onClick={handleConfirmRestore}
              disabled={!restoreReason.trim() || isUpdating}
            >
              되살리기
            </Button>
          </div>
        </div>
      </Modal>

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
          {/* 등급 확정=검수 완료 집계 안내(#1643) — 등급수정 API가 백엔드에서 reviewed=true를
              세우므로(현행 유지), 별도로 '이 하자 검수 확정'을 누르지 않아도 이미 검수로
              집계된다는 것을 여기서 먼저 알린다. */}
          <p className="text-xs text-text-muted">
            등급을 확정하면 이 하자는 별도 확정 없이 검수 완료로 집계됩니다.
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
              {isUpdating ? '저장 중...' : '저장'}
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
