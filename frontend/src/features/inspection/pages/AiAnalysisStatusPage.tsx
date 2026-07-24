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
  jobId: string | null;
  estimatedRemainingMinutes: number | null;
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
    jobId: s.jobId,
    estimatedRemainingMinutes: s.estimatedRemainingMinutes,
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
    jobId: null,
    estimatedRemainingMinutes: null,
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

  // 유효한 inspection id일 때 store에 저장 — SideNavBar의 동적 링크 생성에 사용
  useEffect(() => {
    if (isRealMode && inspectionId !== null) {
      setActiveInspectionId(inspectionId);
    }
  }, [inspectionId, isRealMode, setActiveInspectionId]);

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

  return (
    <div className="flex flex-col gap-6 px-8 py-8">
      <div className="relative flex flex-col gap-10 rounded-[20px] bg-white pb-24 pt-8 shadow-sm outline outline-1 outline-offset-[-1px] outline-neutral-300/40">
        <div className="flex flex-col gap-3 px-8">
          <div className="flex items-baseline justify-between">
            <span className="text-5xl font-semibold text-zinc-900">{status.progressPercent}%</span>
            <p className="m-0 text-[13px] font-medium text-neutral-500">
              {status.totalFileCount === 0 ? (
                '업로드된 이미지가 없습니다'
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
                  {status.jobId !== null && <> · 잡 ID #{status.jobId}</>}
                </>
              )}
            </p>
          </div>
          <div className="h-1.5 overflow-hidden rounded-full bg-zinc-100">
            <div
              className="h-full rounded-full bg-zinc-900 transition-all duration-300"
              style={{ width: `${status.progressPercent}%` }}
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
            {retryError ? (
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
                {/* 코드 리뷰 P2(미해소 지적) — 취소 API가 아직 없다. onClick 없는 클릭 가능한
                    버튼으로 두면 사용자가 눌러도 아무 일도 안 일어나는 오동작이라, InspectionCreatePage의
                    "임시저장" 버튼과 동일하게 disabled+title로 명확히 "준비 중"임을 알린다. */}
                <Button type="button" variant="secondary" disabled title="분석 취소는 준비 중입니다">
                  분석 취소
                </Button>
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
