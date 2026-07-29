import { useState } from 'react';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { Modal } from '../../../shared/components/Modal';
import { Button } from '../../../shared/components/Button';
import { reportApi } from '../api/reportApi';
import { isReportContent } from '../types';
import { useReportVersionHistory } from '../hooks/useReportVersionHistory';
import type { ReportListItem } from '../types';

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

function collectDiffs(current: unknown, selected: unknown, path = ''): DiffEntry[] {
  if (Object.is(current, selected)) return [];
  if (Array.isArray(current) && Array.isArray(selected)) {
    const length = Math.max(current.length, selected.length);
    return Array.from({ length }, (_, index) =>
      collectDiffs(current[index], selected[index], `${path}[${index}]`),
    ).flat();
  }
  if (typeof current === 'object' && current !== null && typeof selected === 'object' && selected !== null) {
    const keys = new Set([...Object.keys(current), ...Object.keys(selected)]);
    return [...keys].flatMap((key) => collectDiffs(
      (current as Record<string, unknown>)[key],
      (selected as Record<string, unknown>)[key],
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
  const [actionMessage, setActionMessage] = useState<string | null>(null);
  // 되돌리기 확인 모달용 — window.confirm 대체
  const [pendingRevert, setPendingRevert] = useState<RevertTarget | null>(null);

  async function handleCompare(versionId: number, version: number) {
    if (!activeReport || version === activeReport.version) return;
    setBusyVersion(versionId);
    setActionMessage(null);
    try {
      const [currentResponse, selectedResponse] = await Promise.all([
        reportApi.getReport(activeReport.id),
        reportApi.getReport(versionId),
      ]);
      if (!isReportContent(currentResponse.data.content) || !isReportContent(selectedResponse.data.content)) {
        setActionMessage('보고서 본문 형식이 달라 비교할 수 없습니다.');
        return;
      }
      setCompareState({
        version,
        diffs: collectDiffs(currentResponse.data.content, selectedResponse.data.content),
      });
    } catch {
      setActionMessage('비교할 보고서 버전을 불러오지 못했습니다.');
    } finally {
      setBusyVersion(null);
    }
  }

  // 되돌리기 버튼 클릭 → 확인 모달 표시 (window.confirm 대체)
  function handleRevert(versionId: number, version: number) {
    if (!activeReport || version === activeReport.version) return;
    if (activeReport.status !== 'DRAFT') {
      setActionMessage('완료된 보고서는 되돌릴 수 없습니다. 편집 중인 보고서에서만 가능합니다.');
      return;
    }
    setPendingRevert({ versionId, version });
  }

  async function confirmRevert() {
    if (!pendingRevert || !activeReport) return;
    const { versionId, version } = pendingRevert;
    setPendingRevert(null);
    setBusyVersion(versionId);
    setActionMessage(null);
    try {
      const selectedResponse = await reportApi.getReport(versionId);
      if (!isReportContent(selectedResponse.data.content)) {
        setActionMessage('선택한 버전의 본문 형식이 달라 되돌릴 수 없습니다.');
        return;
      }
      await reportApi.updateContent(activeReport.id, selectedResponse.data.content);
      setActionMessage(`v${version} 내용으로 되돌렸습니다. 근거 검증을 다시 실행해야 합니다.`);
      setCompareState(null);
      onReverted?.();
    } catch {
      setActionMessage('보고서 되돌리기에 실패했습니다. 현재 상태와 권한을 확인해 주세요.');
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
        title="이전 버전으로 되돌리기"
        closeOnOverlayClick={false}
      >
        <div className="flex flex-col gap-6">
          <p className="text-sm leading-6 text-text-default">
            <span className="font-semibold">v{pendingRevert?.version}</span> 내용으로 되돌리면{' '}
            <span className="font-semibold text-danger">현재 편집 내용이 대체됩니다.</span>
            <br />계속하시겠습니까?
          </p>
          <div className="flex justify-end gap-3">
            <Button variant="secondary" onClick={() => setPendingRevert(null)}>
              취소
            </Button>
            <Button variant="danger" onClick={() => void confirmRevert()}>
              되돌리기
            </Button>
          </div>
        </div>
      </Modal>

      <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-zinc-200 bg-surface-muted p-5">
        <div className="flex items-center justify-between pb-6">
          <h3 className="m-0 text-base font-semibold text-zinc-900">버전 이력</h3>
          <button type="button" aria-label="버전 이력 패널 닫기" onClick={onClose} className="cursor-pointer border-none bg-none text-zinc-500">✕</button>
        </div>

        {!activeReport && <p className="text-sm text-text-muted">행의 ⋮ 메뉴에서 "버전 이력"을 선택하면 여기에 보고서 버전 목록이 표시됩니다.</p>}
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
                const authorName = version.createdByName ?? (isInitial ? '시스템' : `사용자 ${version.version}`);
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
                            되돌리기
                          </button>
                        )}
                      </div>
                    </div>
                  </li>
                );
              })}
            </ul>

            {actionMessage && <p className="mt-4 border-t border-border pt-3 text-xs leading-5 text-text-muted">{actionMessage}</p>}
            {compareState && (
              <section className="mt-4 border-t border-border pt-3" aria-label="보고서 버전 비교 결과">
                <div className="flex items-center justify-between">
                  <h4 className="m-0 text-sm font-semibold text-heading">현재 버전 ↔ v{compareState.version}</h4>
                  <button type="button" onClick={() => setCompareState(null)} className="border-none bg-none text-xs text-text-muted underline">닫기</button>
                </div>
                {compareState.diffs.length === 0 ? (
                  <p className="mt-2 text-xs text-text-muted">본문 내용에 차이가 없습니다.</p>
                ) : (
                  <ul className="m-0 mt-2 max-h-48 list-none space-y-2 overflow-y-auto p-0">
                    {compareState.diffs.map((diff) => (
                      <li key={diff.path} className="text-xs leading-4">
                        <div className="font-semibold text-heading">{diff.path}</div>
                        <div className="text-text-muted">현재: {diff.current}</div>
                        <div className="text-text-muted">v{compareState.version}: {diff.selected}</div>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            )}
          </>
        )}
      </div>
    </div>
  );
}
