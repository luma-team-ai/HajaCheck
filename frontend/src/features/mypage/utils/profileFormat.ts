// 내 프로필 섹션(HAJA-403, #744) 날짜 표기 유틸 — createdAt은 BaseTimeEntity(LocalDateTime)라
// 오프셋 없는 "YYYY-MM-DDTHH:mm:ss[.SSS]"로 온다. new Date()로 파싱하면 날짜만 있는 문자열은 UTC로,
// 시각까지 있는 문자열은 로컬 시간대로 해석되는 JS 스펙 차이 때문에 실행 환경(테스트 CI 등) 시간대에
// 따라 값이 달라질 수 있다(features/admin/utils/formatUserDates.ts formatJoinedAt과 동일 이유) —
// Date 객체를 거치지 않고 문자열을 직접 파싱해 이 문제를 피한다.
const DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})/;

/** 가입일(ISO date/datetime) → "YYYY.MM.DD". 파싱 불가한 값은 원문을 그대로 반환한다. */
export function formatJoinedDate(isoDateTime: string): string {
  const match = DATE_PATTERN.exec(isoDateTime);
  if (!match) return isoDateTime;

  const [, year, month, day] = match;
  return `${year}.${month}.${day}`;
}
