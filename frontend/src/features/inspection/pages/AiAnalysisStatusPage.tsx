import { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { getApiErrorMessage } from '../../../shared/api/types';
import { Button } from '../../../shared/components/Button';
import { CHART_GRADE_COLORS } from '../../../shared/components/charts/palette';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { inspectionApi } from '../api/inspectionApi';
import { useInspectionStore } from '../store/inspectionStore';
import type { AnalysisFileStatus, AnalysisStage, AnalysisStatusResponse } from '../api/inspectionApi.types';
import { useAnalysisStatus } from '../hooks/useAnalysisStatus';
import { buildEmptyAnalysisStatus } from '../mocks/aiAnalysisStatus.mock';
import type { AiAnalysisStatus } from '../mocks/aiAnalysisStatus.mock';

// 분석이 실제로 "진행 중"인지 — "분석 취소" 버튼을 보여줄지 판단하는 기준(stage 기준).
// upload(시작 전)·done(완료)·failed(종료)는 취소할 대상이 없다.
const IN_PROGRESS_STAGES: ReadonlySet<AnalysisStage> = new Set([
  'frameExtraction',
  'aiDetection',
  'postProcessing',
]);

const STAGES: { key: AnalysisStage; label: string }[] = [
  { key: 'upload', label: '업로드 완료' },
  { key: 'frameExtraction', label: '프레임 추출' },
  { key: 'aiDetection', label: 'AI 탐지' },
  { key: 'postProcessing', label: '후처리' },
  { key: 'done', label: '완료' },
];

const STATUS_BADGE: Record<AnalysisFileStatus, { label: string; bg: string; fg: string; dot: string }> = {
  completed: { label: '완료', bg: '#E8F5E9', fg: '#2E7D32', dot: '#4CAF50' },
  analyzing: { label: '분석중', bg: '#E3F2FD', fg: '#1565C0', dot: '#2196F3' },
  failed: { label: '실패', bg: '#FFEBEE', fg: '#C62828', dot: '#F44336' },
  waiting: { label: '대기', bg: '#F4F4F5', fg: '#7A7582', dot: '#A1A1AA' },
};

interface ViewModel {
  progressPercent: number;
  totalFileCount: number;
  analyzedFileCount: number;
  stage: AnalysisStage;
  files: { id: string; fileName: string; status: AnalysisFileStatus; defectCount: number | null; elapsedOrEta: string }[];
  detectedDefectCount: number;
  riskyCrackCount: number;
  severityDistribution: { grade: 'A' | 'B' | 'C' | 'D' | 'E'; percentage: number; color: string }[];
  failedCount: number;
  // 관리자가 "이 분석이 지금 진행 중인지/끝났는지"를 추적할 때 참조할 식별자 — 별도 "분석 실행"
  // 테이블·잡 개념은 없고(docs/_local 논의 참고), 점검 회차 1개당 분석은 최대 1건만 동시 진행되는
  // "한 번에 하나만" 정책상 inspectionId 자체가 이미 추적 단위로 충분하다. 예전엔 목업 전용
  // jobId(항상 null)가 있었는데 실서비스에서 렌더된 적이 없어(비어있는 화면에서만 쓰임) 그대로
  // 걷어내고 실제 값(점검 회차 ID)으로 교체했다.
  inspectionId: number | null;
  estimatedRemainingMinutes: number | null;
  // 증분 분석(#1654) — done 상태에서 이 값이 0보다 크고 reanalysisAllowed도 true면 "추가 사진 N장
  // 분석" 액션을 노출한다.
  unanalyzedMediaCount: number;
  // 리뷰 P1 픽스(#1654) — REVIEWED/REPORTED 등 확정 상태에서는 unanalyzedMediaCount>0이어도 서버가
  // 재분석 트리거를 항상 거부한다. 이 값이 false면 버튼을 아예 노출하지 않는다(죽은 버튼 방지).
  reanalysisAllowed: boolean;
}

function fromMockStatus(s: AiAnalysisStatus): ViewModel {
  return {
    progressPercent: s.progressPercent,
    totalFileCount: s.totalFileCount,
    analyzedFileCount: s.analyzedFileCount,
    stage: s.currentStage,
    files: s.files,
    detectedDefectCount: s.detectedDefectCount,
    riskyCrackCount: s.riskyProgressiveCrackCount,
    severityDistribution: s.severityDistribution,
    failedCount: s.failedCount,
    // 목업은 특정 점검 회차와 연결되지 않는 경로(사이드바 직접 진입)에서만 쓰인다 — 추적할
    // 회차 자체가 없으므로 null.
    inspectionId: null,
    estimatedRemainingMinutes: s.estimatedRemainingMinutes,
    // 목업 경로는 특정 회차와 연결되지 않아(inspectionId=null) 애초에 "추가 사진 분석" 액션을
    // 트리거할 수 없으므로 항상 0/false.
    unanalyzedMediaCount: 0,
    reanalysisAllowed: false,
  };
}

// 코드 리뷰 P3 — 등급별 퍼센트를 Math.round로 각각 독립 반올림하면(예: 1/1/1건 → 33.33%씩)
// 33+33+33=99%처럼 합계가 100%에서 어긋나 스택 바에 빈 틈이 남거나 넘칠 수 있다. 최대 나머지
// (largest-remainder) 방식 — 먼저 내림한 뒤, 버려진 소수부(나머지)가 큰 등급부터 1%씩 배분해
// 정수 퍼센트의 합이 항상 100이 되도록 맞춘다. 렌더 로직과 분리된 순수 함수라 직접 테스트한다.
export function computeSeverityPercentages(
  counts: Record<'A' | 'B' | 'C' | 'D' | 'E', number>,
): Record<'A' | 'B' | 'C' | 'D' | 'E', number> {
  const grades = ['A', 'B', 'C', 'D', 'E'] as const;
  const total = grades.reduce((sum, grade) => sum + counts[grade], 0);
  if (total === 0) {
    return { A: 0, B: 0, C: 0, D: 0, E: 0 };
  }

  const raw = grades.map((grade) => (counts[grade] / total) * 100);
  const floors = raw.map(Math.floor);
  const leftover = 100 - floors.reduce((sum, value) => sum + value, 0);

  // 나머지(소수부)가 큰 등급부터 leftover(정수, 0 이상 grades.length 미만)개만큼 +1 — 동률이면
  // A→E 순서로 결정론적으로 배분한다(테스트 재현성).
  const byRemainderDesc = grades
    .map((grade, index) => ({ grade, index, remainder: raw[index] - floors[index] }))
    .sort((a, b) => b.remainder - a.remainder || a.index - b.index);

  const result: Record<'A' | 'B' | 'C' | 'D' | 'E', number> = { A: floors[0], B: floors[1], C: floors[2], D: floors[3], E: floors[4] };
  for (let i = 0; i < leftover; i++) {
    result[byRemainderDesc[i].grade] += 1;
  }
  return result;
}

function fromRealStatus(s: AnalysisStatusResponse): ViewModel {
  const grades = ['A', 'B', 'C', 'D', 'E'] as const;
  const totalGraded = grades.reduce((sum, grade) => sum + s.severityDistribution[grade], 0);
  const percentages = computeSeverityPercentages(s.severityDistribution);
  const severityDistribution =
    totalGraded === 0
      ? []
      : grades.map((grade) => ({
          grade,
          percentage: percentages[grade],
          color: CHART_GRADE_COLORS[grade],
        }));

  return {
    progressPercent: s.progressPercent,
    totalFileCount: s.totalFileCount,
    analyzedFileCount: s.analyzedFileCount,
    stage: s.stage,
    files: s.files.map((f) => ({
      id: String(f.mediaId),
      fileName: f.fileName,
      status: f.status,
      defectCount: f.defectCount,
      elapsedOrEta: f.elapsedOrEta,
    })),
    detectedDefectCount: s.detectedDefectCount,
    riskyCrackCount: s.riskyCrackCount,
    severityDistribution,
    failedCount: s.failedCount,
    inspectionId: s.inspectionId,
    estimatedRemainingMinutes: null,
    unanalyzedMediaCount: s.unanalyzedMediaCount,
    reanalysisAllowed: s.reanalysisAllowed,
  };
}

// AI 분석 실행/상태(dev-05-04) — URL에 점검 회차 :id가 있으면 실제 백엔드를 폴링하고(점검 생성 →
// AI 분석 시작을 거친 정상 플로우), 없으면(사이드바 "AI 분석 실행/상태" 직접 진입, /inspections/
// ai-analysis 정적 경로) 항상 빈 상태만 보여준다 — 그 경로는 특정 회차와 연결되지 않아 폴링할
// 대상이 없다(가짜 진행률을 지어내지 않음).
export function AiAnalysisStatusPage() {
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const inspectionId = id ? Number(id) : null;
  const isRealMode = inspectionId !== null && !Number.isNaN(inspectionId);
  const setActiveInspectionId = useInspectionStore((state) => state.setActiveInspectionId);

  const { data: realStatus, isLoading, isError, refetch } = useAnalysisStatus(isRealMode ? inspectionId : null);
  const [isRetrying, setIsRetrying] = useState(false);
  const [retryError, setRetryError] = useState<string | null>(null);
  const [isCancelling, setIsCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  // 유효한 inspection id일 때 store에 저장 — SideNavBar의 동적 링크 생성에 사용
  useEffect(() => {
    if (isRealMode && inspectionId !== null) {
      setActiveInspectionId(inspectionId);
    }
  }, [inspectionId, isRealMode, setActiveInspectionId]);

  // 이탈해도 안전하게 계속 진행(정책 변경, 2026-07-28 팀 리뷰 반영) — 예전 "한 번에 하나만" 정책
  // (2026-07-27)은 사이드바 등 앱 내부 이동 시 확인창을 띄우고 분석을 취소(DELETE)했는데, 브라우저
  // 탭을 그냥 닫는 경우는 애초에 잡을 수 없어서(beforeunload 핸들러 없음) "같은 이탈인데 방법에 따라
  // 결과가 다른" 비일관이 있었다. 게다가 activeInspectionId가 이제 localStorage에 영속화되므로
  // (inspectionStore.ts) 언제든 이 화면으로 돌아와 진행 상황을 이어볼 수 있어, 이동 자체를 막을
  // 이유가 없다 — 분석은 서버(AI 서버 + 워커)에서 이미 독립적으로 진행 중이었다. 명시적으로 취소하고
  // 싶으면 아래 "분석 취소" 버튼(handleCancelClick)을 쓴다.
  const isInProgress = isRealMode && realStatus !== undefined && IN_PROGRESS_STAGES.has(realStatus.stage);

  if (isRealMode && isLoading) {
    return <LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />;
  }
  if (isRealMode && (isError || !realStatus)) {
    return (
      <div className="px-8 py-8 text-base text-neutral-600">분석 상태를 불러오지 못했습니다.</div>
    );
  }

  const status = isRealMode && realStatus ? fromRealStatus(realStatus) : fromMockStatus(buildEmptyAnalysisStatus());
  const currentStageIndex = STAGES.findIndex((stage) => stage.key === status.stage);
  const isDone = status.stage === 'done';
  // 코드 리뷰 P2 — 워커가 이미지 전체 실패로 롤백하면 stage가 'failed'다. useAnalysisStatus는 이미
  // 이 상태에서 폴링을 멈추므로(무한 "진행 중 0%" 방지), 화면에서도 명확히 실패로 안내하고
  // 재시도 경로(POST /analyze 재호출)를 열어둔다 — 안 그러면 화면 이탈 말고는 빠져나갈 길이 없다.
  const isFailed = status.stage === 'failed';
  // 코드 리뷰 P2(막다른 길 수정) — InspectionCreatePage가 POST /analyze 트리거 실패를 조용히
  // 삼키고 이 화면으로 이동하면, 분석이 아예 시작되지 않아 stage가 'upload'(rebuildFromDb의
  // "분석된 적 없음" 분기)로 재구성된다. 이전에는 이 상태에서 분석을 시작/재시도할 버튼이 전혀
  // 없어(취소·검수시작 둘 다 disabled) 사용자가 화면을 이탈하는 것 말고는 빠져나갈 길이 없었다.
  const isPreAnalysis = status.stage === 'upload';
  // 증분 분석(#1654) — done인데 아직 분석 안 된 원본 사진이 남아있는 경우. 리뷰 P2 — 이 값 자체는
  // reanalysisAllowed와 무관하게 "진행률 표시를 완료 뱃지+증분 안내로 바꿀지"를 결정한다(REVIEWED/
  // REPORTED 회차라도 done인데 progressPercent가 낮게 보이는 모순은 똑같이 발생하므로).
  const hasUnanalyzedOnDone = isDone && status.unanalyzedMediaCount > 0;
  // 리뷰 P1 픽스 — "추가 사진 분석" 액션 버튼은 unanalyzedMediaCount>0 만으로 노출하지 않는다.
  // REVIEWED/REPORTED 등 확정 상태(reanalysisAllowed=false)에서는 클릭해도 서버가 항상
  // ANALYSIS_NOT_ALLOWED로 거부하는 죽은 버튼이 되므로, 두 조건을 모두 만족할 때만 노출한다.
  const canTriggerIncrementalAnalysis = hasUnanalyzedOnDone && isRealMode && status.reanalysisAllowed;

  const handleRetry = async () => {
    if (!isRealMode || inspectionId === null || isRetrying) {
      return;
    }
    setIsRetrying(true);
    setRetryError(null);
    try {
      await inspectionApi.startAnalysis(inspectionId);
      await refetch();
    } catch (error) {
      // 코드 리뷰 P3 — axios 인터셉터가 던지는 ApiError는 Error 서브클래스가 아니라
      // `error instanceof Error`가 항상 false였다(서버가 준 구체 사유가 대체 문구에 가려짐).
      setRetryError(getApiErrorMessage(error, '재시도에 실패했습니다.'));
    } finally {
      setIsRetrying(false);
    }
  };

  // "분석 취소" 버튼 클릭 — 이탈 확인창의 취소와 달리 페이지를 벗어나지 않고 그대로 남아,
  // 취소 후 재조회(refetch)로 "분석 시작 전" 화면으로 되돌아온다.
  const handleCancelClick = async () => {
    if (!isRealMode || inspectionId === null || isCancelling) {
      return;
    }
    setIsCancelling(true);
    setCancelError(null);
    try {
      await inspectionApi.cancelAnalysis(inspectionId);
      await refetch();
    } catch (error) {
      setCancelError(getApiErrorMessage(error, '분석 취소에 실패했습니다.'));
    } finally {
      setIsCancelling(false);
    }
  };

  return (
    <div className="flex flex-col gap-6 px-8 py-8">
      <div className="relative flex flex-col gap-10 rounded-[20px] bg-white pb-24 pt-8 shadow-sm outline outline-1 outline-offset-[-1px] outline-neutral-300/40">
        <div className="flex flex-col gap-3 px-8">
          <div className="flex items-baseline justify-between">
            {/* 리뷰 P2 — done인데 unanalyzedMediaCount>0(증분 분석 대상 남음)이면 원시 progressPercent를
                그대로 보여주지 않는다. 이 회차의 "과거 분석 실행"은 이미 100% 끝났고, 남은 건 그 이후
                추가 업로드된 사진의 별도 증분 분석이라 "완료 뱃지 + 진행률 100%바 + 증분 안내"가
                실제 상태를 더 정확히 반영한다 — 그대로 두면 "완료(done)"인데 진행률이 낮게 보이는
                모순이 생긴다. */}
            <span className="text-5xl font-semibold text-zinc-900">
              {hasUnanalyzedOnDone ? '완료' : `${status.progressPercent}%`}
            </span>
            <p className="m-0 text-[13px] font-medium text-neutral-500">
              {status.totalFileCount === 0 ? (
                '업로드된 이미지가 없습니다'
              ) : hasUnanalyzedOnDone ? (
                <>
                  기존 <span className="text-zinc-900">{status.analyzedFileCount}장</span> 분석 완료 ·
                  추가 업로드된 <span className="text-zinc-900">{status.unanalyzedMediaCount}장</span>은
                  아직 분석되지 않았습니다
                  {status.inspectionId !== null && <> · 점검 ID #{status.inspectionId}</>}
                </>
              ) : (
                <>
                  <span className="text-zinc-900">{status.totalFileCount}장</span> 중{' '}
                  {status.analyzedFileCount}장 분석 완료
                  {status.estimatedRemainingMinutes !== null && (
                    <>
                      {' '}
                      · 예상 남은 시간 약{' '}
                      <span className="text-zinc-900">{status.estimatedRemainingMinutes}분</span>
                    </>
                  )}
                  {status.inspectionId !== null && <> · 점검 ID #{status.inspectionId}</>}
                </>
              )}
            </p>
          </div>
          <div className="h-1.5 overflow-hidden rounded-full bg-zinc-100">
            <div
              className="h-full rounded-full bg-zinc-900 transition-all duration-300"
              style={{ width: `${hasUnanalyzedOnDone ? 100 : status.progressPercent}%` }}
            />
          </div>
        </div>

        <div className="relative flex items-center justify-between px-8">
          <div className="absolute left-8 right-8 top-3 h-px bg-neutral-200" />
          {STAGES.map((stage, index) => {
            const isStageDone = index < currentStageIndex;
            const isCurrent = index === currentStageIndex;
            return (
              <div key={stage.key} className="relative flex flex-col items-center gap-2 bg-white px-2">
                {isStageDone ? (
                  <span className="flex size-6 items-center justify-center rounded-full bg-zinc-900 text-xs text-white">
                    ✓
                  </span>
                ) : isCurrent ? (
                  <span className="flex size-6 items-center justify-center rounded-full border-2 border-zinc-900 bg-white">
                    <span className="size-2 rounded-full bg-zinc-900" />
                  </span>
                ) : (
                  <span className="size-6 rounded-full border border-neutral-200 bg-zinc-100" />
                )}
                <span
                  className={`text-xs ${
                    isStageDone || isCurrent ? 'font-medium text-zinc-900' : 'font-medium text-neutral-400'
                  } ${isCurrent ? 'font-semibold' : ''}`}
                >
                  {stage.label}
                </span>
              </div>
            );
          })}
        </div>

        <div className="flex gap-8 px-8">
          <div className="flex flex-1 flex-col gap-4">
            <h2 className="m-0 text-[15px] font-medium text-zinc-900">이미지별 처리 현황</h2>
            <div className="overflow-hidden rounded-xl border border-neutral-200">
              <table className="w-full border-collapse text-left">
                <thead>
                  <tr className="border-b border-neutral-200 bg-neutral-50 text-[13px] text-neutral-500">
                    <th className="w-16 px-4 py-2.5 font-medium" aria-hidden="true" />
                    <th className="px-4 py-2.5 font-medium">파일명</th>
                    <th className="px-4 py-2.5 font-medium">상태</th>
                    <th className="px-4 py-2.5 font-medium">탐지된 하자</th>
                    <th className="px-4 py-2.5 text-right font-medium">소요/예상</th>
                  </tr>
                </thead>
                <tbody>
                  {status.files.length === 0 && (
                    <tr>
                      <td colSpan={5} className="px-4 py-6 text-center text-[13px] text-neutral-400">
                        아직 업로드된 이미지가 없습니다
                      </td>
                    </tr>
                  )}
                  {status.files.map((file) => {
                    const badge = STATUS_BADGE[file.status];
                    const isWaiting = file.status === 'waiting';
                    return (
                      <tr
                        key={file.id}
                        className={`border-t border-neutral-100 ${isWaiting ? 'opacity-60' : ''}`}
                      >
                        <td className="px-4 py-1">
                          <span className="flex size-8 items-center justify-center rounded-full bg-neutral-200 text-xs" aria-hidden="true">
                            🖼️
                          </span>
                        </td>
                        <td className="px-4 py-1 font-mono text-xs text-zinc-900">{file.fileName}</td>
                        <td className="px-4 py-1">
                          <span
                            className="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px] font-medium"
                            style={{ background: badge.bg, color: badge.fg }}
                          >
                            <span className="size-1.5 rounded-full" style={{ background: badge.dot }} />
                            {badge.label}
                          </span>
                        </td>
                        <td className="px-4 py-1 text-[13px] text-zinc-900">
                          {file.defectCount !== null ? `${file.defectCount}건` : '-'}
                        </td>
                        <td
                          className="px-4 py-1 text-right text-[13px]"
                          style={{ color: file.status === 'failed' ? '#BA1A1A' : '#7A7582' }}
                        >
                          {file.elapsedOrEta}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>

          <div className="flex w-72 flex-col gap-4">
            <h2 className="m-0 text-[15px] font-medium text-zinc-900">실시간 탐지 요약</h2>
            <div className="flex flex-col gap-6 rounded-2xl border border-neutral-200 bg-neutral-50 p-6">
              <div className="flex items-start justify-between">
                <div className="flex flex-col gap-1">
                  <span className="text-xs font-medium text-neutral-500">현재까지 탐지된 하자</span>
                  <span className="text-[32px] font-bold leading-none text-zinc-900">
                    {status.detectedDefectCount}
                  </span>
                </div>
                <span className="rounded-full border border-neutral-200 bg-white px-2 py-1 text-[10px] font-medium text-zinc-900">
                  실시간 집계
                </span>
              </div>

              <div className="flex flex-col gap-1">
                <span className="text-xs font-medium text-neutral-500">위험 진행성 균열</span>
                <span className="text-xl font-semibold text-zinc-900">{status.riskyCrackCount}</span>
              </div>

              <div className="flex flex-col gap-2 border-t border-neutral-200/50 pt-6">
                <span className="text-xs text-neutral-500">심각도 분포 (A-E)</span>
                {status.severityDistribution.length > 0 ? (
                  <>
                    <div className="flex h-3 overflow-hidden rounded-full">
                      {status.severityDistribution.map((entry) => (
                        <div
                          key={entry.grade}
                          style={{ width: `${entry.percentage}%`, background: entry.color }}
                        />
                      ))}
                    </div>
                    <div className="flex flex-wrap justify-between gap-x-3 gap-y-1">
                      {status.severityDistribution.map((entry) => (
                        <div key={entry.grade} className="flex items-center gap-1">
                          <span className="size-1.5 rounded-full" style={{ background: entry.color }} />
                          <span className="text-[10px] text-neutral-500">
                            {entry.grade} ({entry.percentage}%)
                          </span>
                        </div>
                      ))}
                    </div>
                  </>
                ) : (
                  <p className="m-0 text-xs text-neutral-400">분석이 시작되면 표시됩니다</p>
                )}
              </div>
            </div>
          </div>
        </div>

        <div className="absolute inset-x-0 bottom-0 flex items-center justify-between rounded-b-[20px] border-t border-neutral-200/50 bg-white/70 px-8 py-4 backdrop-blur">
          <p className="m-0 text-[13px] text-neutral-500">
            {cancelError ? (
              <span className="font-medium text-[#BA1A1A]">{cancelError}</span>
            ) : retryError ? (
              <span className="font-medium text-[#BA1A1A]">{retryError}</span>
            ) : isFailed ? (
              <span className="font-medium text-[#BA1A1A]">AI 분석에 실패했습니다. 다시 시도해 주세요.</span>
            ) : status.failedCount > 0 ? (
              <>
                실패 <span className="font-medium text-[#BA1A1A]">{status.failedCount}건</span>
              </>
            ) : isPreAnalysis && isRealMode ? (
              'AI 분석이 아직 시작되지 않았습니다 — 분석 시작을 눌러 주세요'
            ) : isPreAnalysis ? (
              'AI 분석 대기 중입니다'
            ) : isDone ? (
              '분석이 완료됐습니다'
            ) : (
              '실패한 항목이 없습니다'
            )}
          </p>
          <div className="flex items-center gap-3">
            {isFailed ? (
              <Button type="button" variant="primary" onClick={() => void handleRetry()} disabled={isRetrying}>
                {isRetrying ? '재시도 중...' : '재시도'}
              </Button>
            ) : isPreAnalysis && isRealMode ? (
              // 코드 리뷰 P2(막다른 길 수정) — 회차 생성 화면에서 POST /analyze 트리거가 조용히
              // 실패하면 stage가 'upload'로 재구성되는데, 예전에는 여기서 분석을 시작할 방법이
              // 전혀 없었다(취소·검수시작 모두 disabled). handleRetry는 실패 배너의 "재시도"와 동일하게
              // POST /analyze를 다시 호출할 뿐이라 이름과 달리 최초 시작에도 그대로 재사용할 수 있다.
              <Button type="button" variant="primary" onClick={() => void handleRetry()} disabled={isRetrying}>
                {isRetrying ? '분석 시작 중...' : '분석 시작'}
              </Button>
            ) : (
              <>
                {/* "한 번에 하나만" 정책(2026-07-27) — 취소 API 신설로 활성화. isInProgress일 때만
                    보여준다(이미 완료된 분석을 "취소"할 수 있는 것처럼 보이면 혼동을 준다). */}
                {isInProgress && (
                  <Button
                    type="button"
                    variant="secondary"
                    onClick={() => void handleCancelClick()}
                    disabled={isCancelling}
                  >
                    {isCancelling ? '취소 중...' : '분석 취소'}
                  </Button>
                )}
                {/* 증분 분석(#1654) — 분석 완료 후 추가 업로드된 원본 사진이 있으면(과거엔 기존
                    하자가 있는 회차의 재분석이 fail-closed로 막혀 영구 미분석으로 남았다) handleRetry를
                    그대로 재사용해 POST /analyze를 다시 호출한다. 백엔드가 미분석 사진 유무로 증분
                    여부를 자동 판단하므로 별도 엔드포인트나 파라미터가 필요 없다. 리뷰 P1 픽스 —
                    reanalysisAllowed도 함께 확인해 REVIEWED/REPORTED 회차에서는 노출하지 않는다
                    (죽은 버튼 방지, canTriggerIncrementalAnalysis 참고). */}
                {canTriggerIncrementalAnalysis && (
                  <Button
                    type="button"
                    variant="secondary"
                    onClick={() => void handleRetry()}
                    disabled={isRetrying}
                  >
                    {isRetrying ? '분석 중...' : `추가 사진 ${status.unanalyzedMediaCount}장 분석`}
                  </Button>
                )}
                <Button
                  type="button"
                  variant="primary"
                  disabled={!isDone}
                  title={isDone ? undefined : '분석 완료 후 활성화'}
                  onClick={() => {
                    if (isDone && isRealMode) {
                      navigate(`/inspections/${inspectionId}/viewer`);
                    }
                  }}
                >
                  {isDone ? '검수 시작' : '검수 시작 — 분석 완료 후 활성화'}
                </Button>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
