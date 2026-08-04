import type { InspectionDefect, InspectionDefectResponse } from '../types';

export function mapInspectionDefect(response: InspectionDefectResponse): InspectionDefect {
  const { isReviewed, ...fields } = response;
  return { ...fields, reviewed: isReviewed };
}
