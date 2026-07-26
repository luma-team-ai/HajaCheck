import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { AIErrorFallback } from '../../../shared/components/AIErrorFallback';
import { AILoadingIndicator } from '../../../shared/components/AILoadingIndicator';
import { Button } from '../../../shared/components/Button';
import { DistributionBar } from '../../../shared/components/charts/DistributionBar';
import { useInspectionResultReal } from '../../inspection/hooks/useInspectionResultReal';
import type { DefectType } from '../../inspection/types';
import { reportApi, type ReportSummaryResponse } from '../api/reportApi';

// 등급 라벨 — 이 페이지 전용 상수 (다른 feature와의 직접 import 금지 — #832)
const GRADE_LABELS: Record<string, string> = {
  A: '경미',
  B: '양호',
  C: '보통',
  D: '주의',
  E: '심각',
};

// 등급별 색상 (Figma 기준)
const GRADE_COLORS: Record<string, string> = {
  A: '#22c55e', // 초록
  B: '#3b82f6', // 파랑
  C: '#eab308', // 노랑
  D: '#f97316', // 주황
  E: '#ef4444', // 빨강
};

const GRADE_ORDER = ['A', 'B', 'C', 'D', 'E'] as const;

// 유형별 카드 — Figma는 5종을 항상 고정 노출하므로 0건 유형도 렌더한다(AI 자동탐지는 3종이고
// 누수·백태/도장 손상은 수동 추가로만 생기지만, 칸이 사라지면 레이아웃이 흔들린다).
// `type`은 백엔드가 내려주는 DefectType(한글) 원본값이라 매칭 키로 그대로 쓰고,
// `label`은 Figma 표기(가운뎃점·띄어쓰기)라 따로 둔다 — 둘을 합치면 매칭이 조용히 깨진다.
const DEFECT_TYPES: { type: DefectType; label: string }[] = [
  { type: '균열', label: '균열' },
  { type: '박리박락', label: '박리·박락' },
  { type: '누수·백태', label: '누수·백태' },
  { type: '철근노출', label: '철근 노출' },
  { type: '도장 손상', label: '도장 손상' },
];

export function ReportEntryPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const inspectionId = Number(id);

  // 데이터 조회
  const { data, isLoading, isError, refetch } = useInspectionResultReal(inspectionId);

  // 보고서 설정 — 백엔드 POST /inspections/{id}/reports가 옵션을 받지 않아 아직 전송되지 않는다(#876 범위 밖).
  // 템플릿·언어는 선택지가 하나뿐이라 상태가 아니라 상수로 둔다(고를 게 없는데 setter를 두면 죽은 코드가 된다).
  const template = '정밀안전점검 표준';
  const language = '국문';
  const [sections, setSections] = useState({
    overview: true,
    summary: true,
    details: true,
    recommendation: true,
    opinion: false,
  });
  const [includePhoto, setIncludePhoto] = useState(true);

  // UI 상태
  const [isGenerating, setIsGenerating] = useState(false);
  const [reports, setReports] = useState<ReportSummaryResponse[]>([]);
  const [reportsLoading, setReportsLoading] = useState(false);
  const abortControllerRef = useRef<AbortController | null>(null);

  // 클린업
  useEffect(() => {
    return () => {
      abortControllerRef.current?.abort();
    };
  }, []);

  // 최근 작업 내역 조회
  useEffect(() => {
    if (!Number.isInteger(inspectionId) || inspectionId <= 0) return;

    const controller = new AbortController();
    abortControllerRef.current = controller;

    setReportsLoading(true);
    reportApi
      .listReports(inspectionId, controller.signal)
      .then((response) => {
        if (!controller.signal.aborted) {
          setReports(response.data);
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setReports([]);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setReportsLoading(false);
        }
      });
  }, [inspectionId]);

  // 등급 분포 계산
  const gradeDistribution = data
    ? data.defects.reduce(
        (acc, d) => {
          acc[d.grade] = (acc[d.grade] || 0) + 1;
          return acc;
        },
        {} as Record<string, number>,
      )
    : {};

  // 유형별 최고 등급 계산 (심각도 순: E > D > C > B > A)
  const defectTypeStats = DEFECT_TYPES.map((typeInfo) => {
    const typeDefects = data?.defects.filter((d) => d.type === typeInfo.type) || [];
    const count = typeDefects.length;
    // 심각도가 높은 순서 (E가 가장 높음)
    const severityOrder = { E: 5, D: 4, C: 3, B: 2, A: 1 };
    const maxGrade =
      typeDefects.length > 0
        ? typeDefects.reduce((max, d) => {
            const maxSev = severityOrder[max as keyof typeof severityOrder] || 0;
            const curSev = severityOrder[d.grade as keyof typeof severityOrder] || 0;
            return curSev > maxSev ? d.grade : max;
          }, typeDefects[0].grade)
        : null;
    return { ...typeInfo, count, maxGrade };
  });

  // 전체 최고 등급 (가장 심각한 등급)
  const maxGrade = data
    ? ['E', 'D', 'C', 'B', 'A'].find((grade) => gradeDistribution[grade] && gradeDistribution[grade] > 0) ||
      null
    : null;

  // 예상 생성 시간 계산 (로직: 확정 하자 건수 기반의 단순 추정)
  // ponytail: 근거 없는 추정식. 백엔드 제공 데이터가 없으므로 하드코딩.
  // 실제 성능 데이터를 바탕으로 조정 필요.
  const estimatedMinutes = Math.max(1, Math.ceil((data?.reviewedCount || 0) / 50));

  const handleGenerateReport = useCallback(async () => {
    if (!data || data.reviewedCount !== data.totalCount || isGenerating) return;

    setIsGenerating(true);
    try {
      const response = await reportApi.generateReportDraft(inspectionId);
      // 생성 완료 후 편집 화면으로 이동
      navigate(`/inspections/${inspectionId}/reports/generate?reportId=${response.data.id}`);
    } catch (error) {
      const msg = error instanceof Error ? error.message : '보고서 생성에 실패했습니다.';
      alert(msg);
      setIsGenerating(false);
    }
  }, [inspectionId, data, isGenerating, navigate]);

  const handleEditReport = useCallback(
    (reportId: number) => {
      navigate(`/inspections/${inspectionId}/reports/generate?reportId=${reportId}`);
    },
    [inspectionId, navigate],
  );

  if (!Number.isInteger(inspectionId) || inspectionId <= 0) {
    return <div className="p-5 text-red-600">잘못된 접근입니다. 유효한 검사 ID를 확인하세요.</div>;
  }

  if (isLoading) return <AILoadingIndicator message="점검 결과를 분석 중입니다..." />;
  if (isError) return <AIErrorFallback onRetry={() => void refetch()} />;
  if (!data) return <div className="p-5 text-red-600">데이터를 불러올 수 없습니다.</div>;

  return (
    <div className="flex flex-col gap-8 pb-48">
      {/* 1. Title Section */}
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-3xl font-medium tracking-tight text-black">
            점검 회차 요약 — {data.roundNo}회차
          </h1>
          <p className="mt-2 text-base font-medium text-text-muted">
            검수가 완료된 하자를 바탕으로 점검 보고서 초안을 생성합니다.
          </p>
        </div>
        <div className="flex gap-3">
          <Button
            variant="secondary"
            size="md"
            onClick={() => navigate(`/inspections/${inspectionId}/viewer`)}
          >
            회차 상세
          </Button>
          <Button
            variant="primary"
            size="md"
            disabled={data.reviewedCount !== data.totalCount}
            title={
              data.reviewedCount !== data.totalCount
                ? `검수를 완료해야 합니다 (${data.reviewedCount}/${data.totalCount})`
                : undefined
            }
            onClick={handleGenerateReport}
          >
            보고서 생성
          </Button>
        </div>
      </div>

      {/* 2. Round Summary Strip */}
      <div className="flex items-center justify-between gap-6 rounded-full border border-border bg-surface-muted px-6 py-2">
        <div className="flex gap-6">
          {/* 이미지 개수 */}
          <div className="flex gap-3 border-r border-border pr-6">
            <div>
              <div className="text-xs font-medium tracking-wide text-text-muted">이미지</div>
              <div className="mt-1 text-xl font-bold text-black">
                {data.media?.length || 0}장
              </div>
            </div>
          </div>

          {/* 확정 하자 */}
          <div className="flex gap-3 border-r border-border pr-6">
            <div>
              <div className="text-xs font-medium tracking-wide text-text-muted">확정 하자</div>
              <div className="mt-1 text-xl font-bold text-black">{data.reviewedCount}</div>
            </div>
          </div>

          {/* 최고 등급 */}
          <div className="flex gap-3 pr-6">
            <div>
              <div className="text-xs font-medium tracking-wide text-text-muted">최고 등급</div>
              <div className="mt-1 flex items-center gap-2">
                {maxGrade ? (
                  <div
                    className="flex h-6 w-6 items-center justify-center rounded-full text-sm font-bold text-white"
                    style={{ backgroundColor: GRADE_COLORS[maxGrade] }}
                    title={`${maxGrade}등급 · ${GRADE_LABELS[maxGrade]}`}
                  >
                    {maxGrade}
                  </div>
                ) : (
                  <span className="text-text-muted">없음</span>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* 검수 완료율 */}
        <div className="flex items-center gap-3">
          <div className="text-right">
            <div className="text-xs font-medium tracking-wide text-text-muted">검수 완료율</div>
            <div className="mt-1 text-xl font-bold text-black">100%</div>
          </div>
          <div className="inline-flex items-center gap-1 rounded-full bg-success-soft-bg px-3 py-1">
            <div className="h-1.5 w-1.5 rounded-full bg-success" />
            <span className="text-xs font-medium text-success">완료</span>
          </div>
        </div>
      </div>

      {/* 3. Defect Summary Block */}
      <div className="flex flex-col gap-6">
        <h2 className="flex items-center gap-2 text-xl font-medium text-black">
          <span>하자 현황</span>
        </h2>

        {/* 등급별 분포 카드 */}
        <div className="rounded-3xl border border-border bg-white p-6">
          <div className="mb-3 text-xs font-medium tracking-wide text-text-muted">등급별 분포</div>

          {/* 분포 바 — 공용 DistributionBar 재사용. 다만 이 컴포넌트의 기본 범례는 "라벨 (퍼센트%)"라
              Figma의 "A (13)"(건수) 표기와 달라서, 범례만 끄고 아래에서 직접 렌더한다. */}
          <div className="mb-4">
            <DistributionBar
              ariaLabel="하자 등급별 분포"
              height={16}
              showLegend={false}
              segments={GRADE_ORDER.map((grade) => ({
                key: grade,
                label: grade,
                percent: ((gradeDistribution[grade] || 0) / (data.totalCount || 1)) * 100,
                color: GRADE_COLORS[grade],
              }))}
            />
          </div>

          {/* 범례 */}
          <div className="flex flex-wrap gap-2">
            {GRADE_ORDER.map((grade) => {
              const count = gradeDistribution[grade] || 0;
              return (
                <div
                  key={grade}
                  className="flex items-center gap-2 rounded-full border border-border bg-surface-muted px-3 py-1.5"
                >
                  <div
                    className="h-2 w-2 rounded-full"
                    style={{ backgroundColor: GRADE_COLORS[grade] }}
                  />
                  <span className="text-xs font-medium text-black">
                    {grade} ({count})
                  </span>
                </div>
              );
            })}
          </div>
        </div>

        {/* 유형별 카드 */}
        <div className="flex gap-4 overflow-x-auto">
          {defectTypeStats.map((stat) => (
            <div
              key={stat.type}
              data-testid={`defect-type-card-${stat.type}`}
              className="min-w-max flex-shrink-0 rounded-3xl border border-border bg-white p-4"
            >
              <div className="mb-3 flex items-start justify-between">
                <div className="text-sm font-medium text-text-default">{stat.label}</div>
                {stat.maxGrade && (
                  <div
                    className="flex h-5 w-5 items-center justify-center rounded-full text-xs font-bold text-white"
                    style={{ backgroundColor: GRADE_COLORS[stat.maxGrade] }}
                    title={`${stat.maxGrade}등급 · ${GRADE_LABELS[stat.maxGrade]}`}
                  >
                    {stat.maxGrade}
                  </div>
                )}
              </div>
              <div className="text-3xl font-semibold text-black">{stat.count}</div>
            </div>
          ))}
        </div>
      </div>

      {/* 4. Report Options */}
      <div className="flex flex-col gap-6">
        <div className="flex items-center justify-between">
          <h2 className="text-xl font-medium text-black">보고서 설정</h2>
          <div className="inline-flex items-center gap-1 rounded-full bg-surface-muted px-3 py-1">
            <span className="text-xs text-text-muted">
              AI가 수치를 자동 대조합니다 (Grounding Check)
            </span>
          </div>
        </div>

        <div className="rounded-3xl border border-border bg-surface p-6">
          {/* 템플릿 & 언어 */}
          <div className="mb-6 flex gap-4">
            <div className="flex-1">
              <div className="mb-2 text-xs font-medium tracking-wide text-text-muted">템플릿 선택</div>
              <button
                className="w-full rounded-full border border-border bg-white px-4 py-2.5 text-left text-sm font-medium text-black"
                disabled
              >
                {template}
              </button>
            </div>

            <div className="w-px bg-border" />

            <div className="flex-1">
              <div className="mb-2 text-xs font-medium tracking-wide text-text-muted">언어</div>
              <button
                className="w-full rounded-full border border-border bg-white px-4 py-2.5 text-left text-sm font-medium text-black"
                disabled
              >
                {language}
              </button>
            </div>
          </div>

          {/* 섹션 선택 */}
          <div className="mb-6">
            <div className="mb-3 text-xs font-medium tracking-wide text-text-muted">
              포함할 섹션 (자동 초안)
            </div>
            <div className="flex flex-wrap gap-2">
              {[
                { key: 'overview', label: '점검 개요' },
                {
                  key: 'summary',
                  label: '하자 현황 요약',
                  disabled: true,
                  title: 'Grounding check 근거 데이터로 필수입니다',
                },
                {
                  key: 'details',
                  label: '유형별 상세',
                  disabled: true,
                  title: 'Grounding check 근거 데이터로 필수입니다',
                },
                { key: 'recommendation', label: '조치 권고' },
                {
                  key: 'opinion',
                  label: '종합 의견',
                  disabled: true,
                  title: '준비 중입니다',
                },
              ].map((sec) => (
                <button
                  key={sec.key}
                  onClick={() => {
                    if (!sec.disabled) {
                      setSections((prev) => ({
                        ...prev,
                        [sec.key as keyof typeof sections]: !prev[sec.key as keyof typeof sections],
                      }));
                    }
                  }}
                  disabled={sec.disabled}
                  title={sec.title}
                  className={`flex gap-2 rounded-full border px-4 py-2 text-sm font-medium transition-all ${
                    sections[sec.key as keyof typeof sections] && !sec.disabled
                      ? 'border-black bg-black text-white'
                      : 'border-border bg-white text-black disabled:bg-surface disabled:text-text-muted'
                  }`}
                >
                  <input
                    type="checkbox"
                    checked={sections[sec.key as keyof typeof sections] || false}
                    disabled={sec.disabled}
                    readOnly
                    className="pointer-events-none"
                  />
                  {sec.label}
                </button>
              ))}
            </div>
          </div>

          {/* 고지 문구 — 이슈 #463 요구항목("AI 초안이며 법정 제출용 아님"을 설정 단계에도 배치).
              생성 버튼을 누르기 전에 읽히도록 설정 블록 안, 액션바보다 위에 둔다. */}
          <p className="mb-4 rounded-2xl bg-warning-soft-bg px-4 py-3 text-xs text-warning-soft-fg">
            생성되는 문서는 <strong className="font-semibold">AI가 작성한 초안</strong>입니다. 법정 제출용
            보고서가 아니며, 점검자의 검토·수정을 거쳐야 합니다.
          </p>

          {/* 대표 사진 토글 */}
          <div className="flex items-center justify-between rounded-full border border-border bg-white p-4">
            <div className="flex flex-col gap-1">
              <div className="text-sm font-medium text-black">대표 사진 자동 삽입</div>
              <div className="text-xs text-text-muted">
                각 유형별 최고 등급 하자의 원본/AI마커 이미지를 첨부합니다.
              </div>
            </div>
            <button
              onClick={() => setIncludePhoto(!includePhoto)}
              className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
                includePhoto ? 'bg-black' : 'bg-border'
              }`}
            >
              <span
                className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                  includePhoto ? 'translate-x-6' : 'translate-x-1'
                }`}
              />
            </button>
          </div>
        </div>
      </div>

      {/* 5. Previous Reports */}
      {!reportsLoading && reports.length > 0 && (
        <div className="rounded-2xl border border-border bg-surface-soft p-5">
          <h3 className="mb-3 flex items-center gap-2 text-sm font-medium text-black">
            <span>최근 작업 내역</span>
          </h3>

          <div className="space-y-2">
            {reports.map((report) => (
              <div
                key={report.id}
                className="flex items-center justify-between rounded-full border border-border bg-white p-3"
              >
                <div className="flex items-center gap-3">
                  <div className="flex h-10 w-10 items-center justify-center rounded-2xl bg-surface-muted">
                    <span className="text-lg">📄</span>
                  </div>
                  <div>
                    <div className="text-sm font-medium text-black">{data.roundNo}회차 보고서 (초안)</div>
                    <div className="text-xs text-text-muted">
                      {new Date(report.createdAt).toLocaleDateString('ko-KR')}
                    </div>
                  </div>
                  <div className="rounded-full bg-surface-muted px-2 py-1">
                    <span className="text-xs font-medium text-text-default">
                      {report.status === 'DRAFT' ? '편집 중' : '확정됨'}
                    </span>
                  </div>
                </div>

                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() => handleEditReport(report.id)}
                >
                  이어서 편집
                </Button>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 6. Sticky Bottom Action Bar */}
      <div className="fixed bottom-0 left-0 right-0 border-t border-border bg-white/80 px-8 py-4 backdrop-blur-md">
        <div className="mx-auto max-w-6xl">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2 text-sm text-text-muted">
              <span>
                확정 하자 {data.reviewedCount}건 기준 · 예상 생성 시간 약 <span className="font-semibold text-black">{estimatedMinutes}분</span>
              </span>
            </div>
            <div className="flex gap-3">
              <Button variant="secondary" size="md" disabled>
                미리보기
              </Button>
              <Button
                variant="primary"
                size="md"
                disabled={data.reviewedCount !== data.totalCount || isGenerating}
                onClick={handleGenerateReport}
              >
                {isGenerating ? '생성 중...' : '보고서 생성 시작'}
              </Button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
