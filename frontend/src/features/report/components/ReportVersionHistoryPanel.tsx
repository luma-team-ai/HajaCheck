import { useState } from 'react';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { Modal } from '../../../shared/components/Modal';
import { Button } from '../../../shared/components/Button';
import { reportApi } from '../api/reportApi';
import { isReportContent, type ManualSection, type ReportContent } from '../types';
import { useReportVersionHistory } from '../hooks/useReportVersionHistory';
import type { ReportListItem } from '../types';
import { sectionLabel, resolveSectionOrder } from '../utils/sectionOrder';

type Props = {
  activeReport: ReportListItem | null;
  onClose: () => void;
  onReverted?: () => void;
};

type DiffEntry = { path: string; current: string; selected: string };

type CompareState = {
  version: number;
  diffs: DiffEntry[];
};

type ActionModalState = {
  title: string;
  message: string;
};

// 되돌리기 확인 모달용
type RevertTarget = { versionId: number; version: number };

function formatShortDate(iso: string): string {
  const date = new Date(iso);
  const mm = String(date.getMonth() + 1).padStart(2, '0');
  const dd = String(date.getDate()).padStart(2, '0');
  const hh = String(date.getHours()).padStart(2, '0');
  const min = String(date.getMinutes()).padStart(2, '0');
  return `${mm}.${dd} ${hh}:${min}`;
}

function displayValue(value: unknown): string {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  return (text ?? '—').length > 180 ? `${text?.slice(0, 177)}…` : text ?? '—';
}

const DIFF_PATH_LABELS: Record<string, string> = {
  overview: '기본현황',
  'overview.purpose': '기본현황 > 점검 목적',
  'overview.facility_summary': '기본현황 > 시설물 개요',
  'overview.scope': '기본현황 > 점검 범위',
  summary: '결과 요약',
  'summary.overall_opinion': '결과 요약 > 종합 의견',
  'summary.total_count': '결과 요약 > 총 하자 수',
  'summary.count_by_grade': '결과 요약 > 등급별 개수',
  'summary.key_findings': '결과 요약 > 주요 발견사항',
  detail: '진단 외관조사결과 기본사항',
  'detail.items': '진단 외관조사결과 기본사항 > 하자 항목',
  recommendation: '보수ㆍ보강(안)',
  'recommendation.items': '보수ㆍ보강(안) > 권고 조치',
  'recommendation.monitoring_points': '보수ㆍ보강(안) > 지속 관찰 부위',
  reportOptions: '보고서 옵션',
  manualSectionValues: '수동 서식 섹션',
  sectionOrder: '섹션 순서',
};

const DIFF_SEGMENT_LABELS: Record<string, string> = {
  defect_type: '하자 유형',
  location: '위치',
  severity_grade: '등급',
  description: '조사 결과',
  cause: '추정 원인',
  target: '대상 부위',
  method: '보수ㆍ보강안',
  priority: '우선순위',
  legal_basis: '적용 근거',
  legal_basis_verified: '근거 검증 여부',
  title: '제목',
  type: '유형',
  data: '내용',
  body: '본문',
  entries: '참여자',
  recipient: '수신자',
  contractDate: '계약 체결일',
  companyName: '발신 업체명',
  companyAddress: '업체 주소',
  representativeName: '대표자',
};

const IGNORED_DIFF_KEYS = new Set(['reportOptions']);

function manualSectionComparableValue(section: ManualSection): Record<string, unknown> {
  return section.data as Record<string, unknown>;
}

function normalizeContentForCompare(content: ReportContent): Record<string, unknown> {
  const manualSections = content.manualSections ?? [];
  return {
    overview: content.overview,
    summary: content.summary,
    detail: content.detail,
    recommendation: content.recommendation,
    manualSectionValues: Object.fromEntries(
      manualSections.map((section) => [section.title, manualSectionComparableValue(section)]),
    ),
    sectionOrder: resolveSectionOrder(content).map((key) => sectionLabel(key, manualSections)),
  };
}

function formatDiffPath(path: string): string {
  if (DIFF_PATH_LABELS[path]) return DIFF_PATH_LABELS[path];
  return path
    .split('.')
    .map((segment) => {
      const arrayMatch = segment.match(/^(.+)\[(\d+)\]$/);
      if (arrayMatch) {
        const [, key, index] = arrayMatch;
        return `${DIFF_SEGMENT_LABELS[key] ?? DIFF_PATH_LABELS[key] ?? key} ${Number(index) + 1}`;
      }
      return DIFF_SEGMENT_LABELS[segment] ?? DIFF_PATH_LABELS[segment] ?? segment.replaceAll('_', ' ');
    })
    .join(' > ');
}

function collectDiffs(current: unknown, selected: unknown, path = ''): DiffEntry[] {
  if (Object.is(current, selected)) return [];
  if (Array.isArray(current) && Array.isArray(selected)) {
    const length = Math.max(current.length, selected.length);
    return Array.from({ length }, (_, index) =>
      collectDiffs(current[index], selected[index], `${path}[${index}]`),
    ).flat();
  }
  if (
    (typeof current === 'object' && current !== null) ||
    (typeof selected === 'object' && selected !== null)
  ) {
    const currentRecord =
      typeof current === 'object' && current !== null && !Array.isArray(current)
        ? (current as Record<string, unknown>)
        : {};
    const selectedRecord =
      typeof selected === 'object' && selected !== null && !Array.isArray(selected)
        ? (selected as Record<string, unknown>)
        : {};
    const keys = new Set([...Object.keys(currentRecord), ...Object.keys(selectedRecord)]);
    return [...keys]
      .filter((key) => !IGNORED_DIFF_KEYS.has(key))
      .flatMap((key) => collectDiffs(
        currentRecord[key],
        selectedRecord[key],
        path ? `${path}.${key}` : key,
      ));
  }
  return [{ path: path || 'content', current: displayValue(current), selected: displayValue(selected) }];
}

// 보고서 목록/이력 관리(#463) 버전 이력 패널. 비교와 되돌리기는 새 엔드포인트를 만들지 않고
// 기존 단건 조회(GET /api/reports/{id})와 본문 수정(PATCH /api/reports/{id}) 계약을 재사용한다.
export function ReportVersionHistoryPanel({ activeReport, onClose, onReverted }: Props) {
  const { data: versions, isLoading, isError } = useReportVersionHistory(activeReport);
  const [compareState, setCompareState] = useState<CompareState | null>(null);
  const [busyVersion, setBusyVersion] = useState<number | null>(null);
  const [actionModal, setActionModal] = useState<ActionModalState | null>(null);
  // 되돌리기 확인 모달용 — window.confirm 대체
  const [pendingRevert, setPendingRevert] = useState<RevertTarget | null>(null);

  async function handleCompare(versionId: number, version: number) {
    if (!activeReport || version === activeReport.version) return;
    setBusyVersion(versionId);
    setActionModal(null);
    try {
      const [currentResponse, selectedResponse] = await Promise.all([
        reportApi.getReport(activeReport.id),
        reportApi.getReport(versionId),
      ]);
      if (!isReportContent(currentResponse.data.content) || !isReportContent(selectedResponse.data.content)) {
        setActionModal({ title: '비교할 수 없음', message: '보고서 본문 형식이 달라 비교할 수 없습니다.' });
        return;
      }
      setCompareState({
        version,
        diffs: collectDiffs(
          normalizeContentForCompare(currentResponse.data.content),
          normalizeContentForCompare(selectedResponse.data.content),
        ),
      });
    } catch {
      setActionModal({ title: '비교 실패', message: '비교할 보고서 버전을 불러오지 못했습니다.' });
    } finally {
      setBusyVersion(null);
    }
  }

  // 되돌리기 버튼 클릭 → 확인 모달 표시 (window.confirm 대체)
  function handleRevert(versionId: number, version: number) {
    if (!activeReport || version === activeReport.version) return;
    if (activeReport.status !== 'DRAFT') {
      setActionModal({
        title: '되돌릴 수 없음',
        message: '완료된 보고서는 되돌릴 수 없습니다. 편집 중인 보고서에서만 가능합니다.',
      });
      return;
    }
    setPendingRevert({ versionId, version });
  }

  async function confirmRevert() {
    if (!pendingRevert || !activeReport) return;
    const { versionId, version } = pendingRevert;
    setPendingRevert(null);
    setBusyVersion(versionId);
    setActionModal(null);
    try {
      const selectedResponse = await reportApi.getReport(versionId);
      if (!isReportContent(selectedResponse.data.content)) {
        setActionModal({ title: '되돌릴 수 없음', message: '선택한 버전의 본문 형식이 달라 되돌릴 수 없습니다.' });
        return;
      }
      await reportApi.updateContent(activeReport.id, selectedResponse.data.content);
      setActionModal({ title: '이전 버전 적용 완료', message: `v${version} 내용으로 되돌렸습니다. 근거 검증을 다시 실행해야 합니다.` });
      setCompareState(null);
      onReverted?.();
    } catch {
      setActionModal({ title: '되돌리기 실패', message: '보고서 되돌리기에 실패했습니다. 현재 상태와 권한을 확인해 주세요.' });
    } finally {
      setBusyVersion(null);
    }
  }

  return (
    <div className="w-72 shrink-0 py-4 pr-4">
      {/* 되돌리기 확인 모달 */}
      <Modal
        open={pendingRevert !== null}
        onClose={() => setPendingRevert(null)}
        title="이전 버전 적용"
        closeOnOverlayClick={false}
      >
        <div className="flex flex-col gap-6">
          <p className="text-sm leading-6 text-text-default">
            <span className="font-semibold">v{pendingRevert?.version}</span> 내용을 현재 보고서에 적용하면{' '}
            <span className="font-semibold text-danger">현재 편집 내용이 대체됩니다.</span>
            <br />계속하시겠습니까?
          </p>
          <div className="flex justify-end gap-3">
            <Button variant="secondary" onClick={() => setPendingRevert(null)}>
              취소
            </Button>
            <Button variant="danger" onClick={() => void confirmRevert()}>
              적용
            </Button>
          </div>
        </div>
      </Modal>

      <Modal
        open={actionModal !== null}
        onClose={() => setActionModal(null)}
        title={actionModal?.title ?? '알림'}
      >
        <div className="flex flex-col gap-6">
          <p className="text-sm leading-6 text-text-default">{actionModal?.message}</p>
          <div className="flex justify-end">
            <Button variant="secondary" onClick={() => setActionModal(null)}>
              확인
            </Button>
          </div>
        </div>
      </Modal>

      <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-zinc-200 bg-surface-muted p-5">
        <div className="flex items-center justify-between pb-6">
          <h3 className="m-0 text-base font-semibold text-zinc-900">변경 이력</h3>
          <button type="button" aria-label="변경 이력 패널 닫기" onClick={onClose} className="cursor-pointer border-none bg-none text-zinc-500">✕</button>
        </div>

        {activeReport && isLoading && <LoadingSpinner className="flex items-center gap-2" />}
        {activeReport && isError && <p className="text-sm text-danger">버전 이력을 불러오지 못했습니다.</p>}

        {activeReport && !isLoading && !isError && (
          <>
            <ul className="m-0 flex list-none flex-col p-0 pt-1">
              {(versions ?? []).map((version, index) => {
                const isCurrent = version.version === activeReport.version;
                const isInitial = version.version === 1;
                const isLast = index === (versions ?? []).length - 1;
                const isBusy = busyVersion === version.id;
                const authorName = version.createdByName || '알 수 없음';
                return (
                  <li key={version.id} className="flex gap-4">
                    <div className="flex w-[18px] shrink-0 flex-col items-center">
                      <div className={`flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded-full ${isCurrent ? 'bg-heading' : 'bg-border'}`} aria-hidden="true">
                        <div className={`h-[8px] w-[8px] rounded-full ${isCurrent ? 'bg-surface' : 'bg-text-muted'}`} />
                      </div>
                      {!isLast && <div className="my-1.5 w-[2px] flex-1 bg-border" aria-hidden="true" />}
                    </div>
                    <div className={`flex flex-1 flex-col gap-1 ${!isLast ? 'pb-6' : ''}`}>
                      <div className="flex items-center justify-between gap-2">
                        <div className="flex items-center gap-1.5">
                          <span className="text-sm font-bold text-heading">v{version.version} {isCurrent ? '현재' : isInitial ? '초안' : ''}</span>
                          {isInitial && <span className="rounded-full bg-indigo-50 px-2 py-0.5 text-[10px] font-semibold text-indigo-600">AI 생성</span>}
                        </div>
                        <span className="text-xs font-normal text-text-muted">{formatShortDate(version.createdAt)}</span>
                      </div>
                      <div className="text-xs font-medium text-text-muted">{authorName}</div>
                      <div className="flex gap-2 pt-1.5">
                        <button type="button" disabled={isCurrent || isBusy} onClick={() => void handleCompare(version.id, version.version)} className="rounded-full border border-border bg-surface px-3.5 py-1 text-xs font-medium text-text-muted shadow-2xs disabled:cursor-not-allowed disabled:opacity-50">
                          {isBusy ? '확인 중…' : '비교'}
                        </button>
                        {!isCurrent && (
                          <button type="button" disabled={isBusy} onClick={() => handleRevert(version.id, version.version)} className="rounded-full border border-border bg-surface px-3.5 py-1 text-xs font-medium text-text-muted shadow-2xs disabled:cursor-not-allowed disabled:opacity-50">
                            이 버전 적용
                          </button>
                        )}
                      </div>
                    </div>
                  </li>
                );
              })}
            </ul>
          </>
        )}
      </div>

      <Modal
        open={compareState !== null}
        onClose={() => setCompareState(null)}
        title={compareState ? `현재 버전 ↔ v${compareState.version}` : '보고서 버전 비교'}
      >
        <section className="w-[min(78vw,920px)]" aria-label="보고서 버전 비교 결과">
          {compareState?.diffs.length === 0 ? (
            <p className="m-0 text-base leading-7 text-text-muted">본문 내용에 차이가 없습니다.</p>
          ) : (
            <ul className="m-0 max-h-[60vh] list-none space-y-4 overflow-y-auto p-0 pr-1">
              {compareState?.diffs.map((diff) => (
                <li key={diff.path} className="rounded-xl border border-border bg-surface-muted p-4 text-base leading-7">
                  <div className="mb-2 text-lg font-semibold text-heading">{formatDiffPath(diff.path)}</div>
                  <div className="grid gap-3 md:grid-cols-2">
                    <div className="rounded-lg bg-surface p-3">
                      <div className="mb-1 text-sm font-semibold text-text-muted">현재</div>
                      <div className="whitespace-pre-wrap text-base text-text-default">{diff.current}</div>
                    </div>
                    <div className="rounded-lg bg-surface p-3">
                      <div className="mb-1 text-sm font-semibold text-text-muted">v{compareState.version}</div>
                      <div className="whitespace-pre-wrap text-base text-text-default">{diff.selected}</div>
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
      </Modal>
    </div>
  );
}
