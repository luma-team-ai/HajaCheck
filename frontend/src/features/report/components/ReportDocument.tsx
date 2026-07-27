import type { InspectionResult } from '../../inspection/types';
import type { ReportDetailResponse } from '../api/reportApi';
import type { ReportContent } from '../types';

type Props = {
  content: ReportContent;
  report: ReportDetailResponse;
  inspectionData?: InspectionResult | null;
};

const gradeTone: Record<string, string> = {
  A: 'bg-gray-400',
  B: 'bg-zinc-900',
  C: 'bg-red-700',
  D: 'bg-orange-600',
  E: 'bg-red-900',
};

function countByDefectType(content: ReportContent) {
  const map = new Map<string, { count: number; grade: string; note: string }>();
  content.detail.items.forEach((item) => {
    const current = map.get(item.defect_type);
    const nextCount = (current?.count ?? 0) + 1;
    map.set(item.defect_type || '기타', {
      count: nextCount,
      grade: current?.grade ?? item.severity_grade,
      note: item.description || item.cause || '-',
    });
  });
  return Array.from(map.entries()).map(([type, value]) => ({ type, ...value }));
}

function Cell({ label, value }: { label: string; value: string }) {
  return (
    <>
      <div className="bg-gray-50 px-2 py-1.5 text-xs font-medium text-zinc-900 outline outline-1 outline-gray-300">
        {label}
      </div>
      <div className="px-2 py-1.5 text-xs font-medium text-zinc-900 outline outline-1 outline-gray-300">
        {value || '-'}
      </div>
    </>
  );
}

export function ReportDocument({ content, report, inspectionData }: Props) {
  const documentDate = new Date(report.createdAt).toLocaleDateString('ko-KR');
  const facilityName = inspectionData?.facilityName ?? content.overview.facility_summary;
  const roundLabel = inspectionData ? `제 ${inspectionData.roundNo}회 점검` : `보고서 v${report.version}`;
  const typeRows = countByDefectType(content);
  const previewMedia = inspectionData?.media.slice(0, 2) ?? [];

  return (
    <article
      aria-label="보고서 문서 미리보기"
      className="flex min-h-[1123px] w-[794px] shrink-0 flex-col bg-white p-12 text-zinc-900 shadow-[0_1px_3px_rgba(0,0,0,0.10),0_20px_40px_rgba(0,0,0,0.05)]"
    >
      <header className="border-b-2 border-zinc-900 pb-4 text-center">
        <h2 className="m-0 text-2xl font-medium leading-8">시설물 외관 점검 보고서</h2>
        <p className="m-0 mt-2 text-xs font-normal leading-4 text-neutral-600">
          문서번호: RPT-{report.id} | 점검일자: {documentDate}
        </p>
      </header>

      <section className="pt-8">
        <h3 className="border-l-4 border-zinc-900 pl-2 text-sm font-medium leading-5">1. 점검 대상 개요</h3>
        <div className="mt-2 grid grid-cols-4 outline outline-1 outline-gray-300">
          <Cell label="시설물명" value={facilityName} />
          <Cell label="점검회차" value={roundLabel} />
          <div className="bg-gray-50 px-2 py-1.5 text-xs font-medium text-zinc-900 outline outline-1 outline-gray-300">
            점검 범위
          </div>
          <div className="col-span-3 px-2 py-1.5 text-xs font-medium text-zinc-900 outline outline-1 outline-gray-300">
            {content.overview.scope || '-'}
          </div>
          <Cell label="점검자" value={report.createdBy ? `사용자 #${report.createdBy}` : '-'} />
          <Cell label="검토 상태" value={report.groundingCheckPassed === true ? '검증 완료' : '검증 대기'} />
        </div>
      </section>

      <section className="pt-8">
        <h3 className="border-l-4 border-zinc-900 pl-2 text-sm font-medium leading-5">2. 하자 현황 요약</h3>
        <p className="mt-2 text-xs font-medium leading-5">{content.summary.overall_opinion}</p>
        <div className="mt-2 outline outline-1 outline-gray-300">
          <div className="grid grid-cols-[144px_128px_160px_1fr] bg-gray-50 text-center text-xs font-medium leading-4">
            <div className="px-2 py-1.5 outline outline-1 outline-gray-300">유형</div>
            <div className="px-2 py-1.5 outline outline-1 outline-gray-300">발견 건수</div>
            <div className="px-2 py-1.5 outline outline-1 outline-gray-300">위험도 (최고)</div>
            <div className="px-2 py-1.5 outline outline-1 outline-gray-300">비고</div>
          </div>
          {typeRows.length > 0 ? (
            typeRows.map((row) => (
              <div key={row.type} className="grid grid-cols-[144px_128px_160px_1fr] text-xs font-medium leading-4">
                <div className="px-2 py-1.5 text-center outline outline-1 outline-gray-300">{row.type}</div>
                <div className="px-2 py-1.5 text-center outline outline-1 outline-gray-300">{row.count}</div>
                <div className="flex items-center justify-center gap-1 px-2 py-1.5 outline outline-1 outline-gray-300">
                  <span className={`h-2 w-2 rounded-full ${gradeTone[row.grade] ?? 'bg-gray-400'}`} />
                  <span>{row.grade || '-'}등급</span>
                </div>
                <div className="px-2 py-1.5 outline outline-1 outline-gray-300">{row.note}</div>
              </div>
            ))
          ) : (
            <div className="px-2 py-3 text-center text-xs text-neutral-600">하자 상세 항목이 없습니다.</div>
          )}
        </div>
      </section>

      <section className="flex-1 pt-8">
        <h3 className="border-l-4 border-zinc-900 pl-2 text-sm font-medium leading-5">3. 주요 결함 사진 대지</h3>
        <div className="mt-3 grid grid-cols-2 gap-4">
          {[0, 1].map((index) => {
            const item = content.detail.items[index];
            const media = previewMedia[index];
            return (
              <figure key={index} className="m-0 bg-gray-50 p-2 outline outline-1 outline-gray-200">
                <div className="relative flex h-60 items-center justify-center overflow-hidden bg-gray-200">
                  {media?.imageUrl ? (
                    <img src={media.imageUrl} alt="" className="h-full w-full object-cover" />
                  ) : (
                    <span className="text-xs text-neutral-500">사진 없음</span>
                  )}
                  {item?.severity_grade && (
                    <span className={`absolute right-2 top-2 rounded-2xl px-1.5 py-0.5 text-[10px] font-bold leading-4 text-white ${gradeTone[item.severity_grade] ?? 'bg-zinc-900'}`}>
                      {item.severity_grade}
                    </span>
                  )}
                </div>
                <figcaption className="pt-1 text-center text-[10px] font-medium leading-4">
                  사진 {index + 1}: {item ? `${item.location} ${item.defect_type}` : '주요 결함 이미지'}
                </figcaption>
              </figure>
            );
          })}
        </div>
      </section>

      <footer className="border-t border-gray-300 pt-4">
        <div className="flex justify-between text-[10px] font-medium leading-4 text-neutral-600">
          <span className="w-32" />
          <span>- 1 -</span>
          <span>CONFIDENTIAL</span>
        </div>
      </footer>
    </article>
  );
}
