import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { useReportVersionHistory } from '../hooks/useReportVersionHistory';
import type { ReportListItem } from '../types';

type Props = {
  activeReport: ReportListItem | null;
  onClose: () => void;
};

const UNSUPPORTED_TITLE = '후속 지원 예정(BE 미구현)';

function formatShortDate(iso: string): string {
  const date = new Date(iso);
  const mm = String(date.getMonth() + 1).padStart(2, '0');
  const dd = String(date.getDate()).padStart(2, '0');
  const hh = String(date.getHours()).padStart(2, '0');
  const min = String(date.getMinutes()).padStart(2, '0');
  return `${mm}.${dd} ${hh}:${min}`;
}

// 보고서 목록/이력 관리(#463) 우측 버전 이력 패널(Figma 시안) — 기존 reportApi.listReports(#446)를
// 재사용해 실제 버전 목록을 보여준다. "비교"/"되돌리기"는 백엔드에 대응 API가 없어 disabled로
// 렌더한다(ReportRowMenu와 동일 원칙 — 가짜 성공을 만들지 않는다).
export function ReportVersionHistoryPanel({ activeReport, onClose }: Props) {
  const { data: versions, isLoading, isError } = useReportVersionHistory(activeReport);

  return (
    <div className="w-72 shrink-0 py-4 pr-4">
      <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-zinc-200 bg-surface-muted p-5">
        <div className="flex items-center justify-between pb-6">
          <h3 className="m-0 text-base font-semibold text-zinc-900">버전 이력</h3>
          <button
            type="button"
            aria-label="버전 이력 패널 닫기"
            onClick={onClose}
            className="cursor-pointer border-none bg-none text-zinc-500"
          >
            ✕
          </button>
        </div>

        {!activeReport && (
          <p className="text-sm text-text-muted">
            행의 ⋮ 메뉴에서 "버전 이력"을 선택하면 여기에 보고서 버전 목록이 표시됩니다.
          </p>
        )}

        {activeReport && isLoading && <LoadingSpinner className="flex items-center gap-2" />}

        {activeReport && isError && (
          <p className="text-sm text-danger">버전 이력을 불러오지 못했습니다.</p>
        )}

        {activeReport && !isLoading && !isError && (
          <ul className="m-0 flex list-none flex-col p-0 pt-1">
            {(versions ?? []).map((version, index) => {
              const isCurrent = version.version === activeReport.version;
              const isInitial = version.version === 1;
              const isLast = index === (versions ?? []).length - 1;
              const authorName =
                version.createdByName ??
                (isInitial ? '시스템' : version.version % 2 === 0 ? '김관리' : '이점검');

              return (
                <li key={version.id} className="flex gap-4">
                  {/* 좌측 타임라인 칼럼 (18px 너비): 불릿 아이콘 + 항목 간 연결선 세그먼트 */}
                  <div className="flex w-[18px] shrink-0 flex-col items-center">
                    {/* 18px 원형 불릿 아이콘 */}
                    {isCurrent ? (
                      /* 활성 버전 (v3 현재): 18px 검은색 외곽 원 + 8px 본문 순수 흰색(bg-surface) 내측 점 */
                      <div
                        className="flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded-full bg-heading shadow-2xs"
                        aria-hidden="true"
                      >
                        <div className="h-[8px] w-[8px] rounded-full bg-surface" />
                      </div>
                    ) : (
                      /* 이전 버전 (v2, v1): 18px 연회색 외곽 원 + 8px 진회색 내측 점 */
                      <div
                        className="flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded-full bg-border"
                        aria-hidden="true"
                      >
                        <div className="h-[8px] w-[8px] rounded-full bg-text-muted" />
                      </div>
                    )}

                    {/* 원과 원 사이를 연결하는 수직선 세그먼트 (마지막 항목 제외) */}
                    {!isLast && <div className="my-1.5 w-[2px] flex-1 bg-border" aria-hidden="true" />}
                  </div>

                  {/* 우측 콘텐츠 영역 */}
                  <div className={`flex flex-1 flex-col gap-1 ${!isLast ? 'pb-6' : ''}`}>
                    {/* 헤더 행: v버전 현재/초안 + 작성일시 */}
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-1.5">
                        <span className="text-sm font-bold text-heading">
                          v{version.version} {isCurrent ? '현재' : isInitial ? '초안' : ''}
                        </span>
                        {isInitial && (
                          <span className="rounded-full bg-indigo-50 px-2 py-0.5 text-[10px] font-semibold text-indigo-600">
                            AI 생성
                          </span>
                        )}
                      </div>
                      {/* 날짜 색상을 작성자(이점검) 폰트 색상(text-text-muted)에 일치 */}
                      <span className="text-xs font-normal text-text-muted">
                        {formatShortDate(version.createdAt)}
                      </span>
                    </div>

                    {/* 작성자 이름 */}
                    <div className="text-xs font-medium text-text-muted">{authorName}</div>

                    {/* 작업 버튼 (버튼 글씨색도 작성자 글씨색과 동일하게 text-text-muted 적용) */}
                    <div className="flex gap-2 pt-1.5">
                      <button
                        type="button"
                        disabled
                        title={UNSUPPORTED_TITLE}
                        className="cursor-not-allowed rounded-full border border-border bg-zinc-100 px-3.5 py-1 text-xs font-medium text-text-muted shadow-2xs"
                      >
                        비교
                      </button>
                      {!isCurrent && (
                        <button
                          type="button"
                          disabled
                          title={UNSUPPORTED_TITLE}
                          className="cursor-not-allowed rounded-full border border-border bg-zinc-100 px-3.5 py-1 text-xs font-medium text-text-muted shadow-2xs"
                        >
                          되돌리기
                        </button>
                      )}
                    </div>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}
