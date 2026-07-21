import { useFacilityDefectActivityLog } from '../hooks/useFacilityDefectActivityLog';

type Props = {
  facilityId: string;
};

// 활동 이력 타임라인 — 로딩/에러/빈 데이터/정상 4상태 처리(React_코드_컨벤션.md §5).
export function FacilityDefectActivityTimeline({ facilityId }: Props) {
  const { data, isLoading, isError, refetch } = useFacilityDefectActivityLog(facilityId);

  return (
    <section className="flex flex-col gap-3 border-t border-border pt-5">
      <h2 className="m-0 text-base font-bold text-heading">활동 이력</h2>
      {isLoading && <p className="m-0 text-sm text-text-muted">불러오는 중...</p>}
      {isError && (
        <button
          type="button"
          onClick={() => refetch()}
          className="self-start text-sm font-semibold text-accent"
        >
          다시 시도
        </button>
      )}
      {!isLoading && !isError && data && data.length === 0 && (
        <p className="m-0 text-sm text-text-muted">활동 이력이 없습니다.</p>
      )}
      {!isLoading && !isError && data && data.length > 0 && (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {data.map((item) => (
            <li key={item.id} className="flex items-center gap-2 text-sm text-text-default">
              <span className="h-1.5 w-1.5 rounded-full bg-heading" aria-hidden="true" />
              {item.message}
              <span className="text-text-muted">· {item.occurredAtLabel}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}