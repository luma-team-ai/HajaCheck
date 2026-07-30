import { REPORT_GRADE_DOT_CLASS } from '../statusClasses';
import type { ReportGradeDotColor } from '../types';

type Props = {
  dots: ReportGradeDotColor[];
};

const GRADE_DOT_LABEL: Record<ReportGradeDotColor, string> = {
  RED: '심각',
  ORANGE: '주의',
  GREEN: '양호',
};

// 보고서 카드의 등급 dots — 신호등 색(빨강/주황/초록) 나열. A~E 문자 등급이 아니라 보고서에 포함된
// 하자 심각도 색 분포를 점으로만 요약 표시한다(Figma 시안, types.ts ReportGradeDotColor 참고).
export function ReportGradeDots({ dots }: Props) {
  return (
    <span className="inline-flex items-center gap-1" aria-label={`등급 분포 ${dots.length}건`}>
      {dots.map((dot, index) => (
        <span
          // 색만 있는 고정 나열(식별자 없음)이라 순서를 보존하는 index 조합 key를 쓴다
          key={`${dot}-${index}`}
          className={`h-2 w-2 rounded-full ${REPORT_GRADE_DOT_CLASS[dot]}`}
          title={GRADE_DOT_LABEL[dot]}
          aria-hidden="true"
        />
      ))}
    </span>
  );
}
