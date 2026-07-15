// 점검주기(개월) 기준으로 오늘부터 다음 점검일을 산정 — FE가 계산해 CreateFacilityRequest에 포함시켜 전송한다.
// 백엔드 FacilityCreateRequest.nextInspectionDueAt은 클라이언트가 보내는 옵션 필드이며,
// FacilityService는 전달받은 값을 그대로 저장할 뿐 점검주기 기반 자동계산 로직을 갖고 있지 않다.
// 서버측 자동산정은 FR-019 `POST /api/facilities/{id}/schedule`(dev-04-03) 소관.
// MSW 목(facilityApi.handlers.ts)과 폼 제출 변환(validateFacilityForm.ts)이 서로 다른 규칙으로 어긋나지
// 않도록 이 유틸 하나로 공용화한다 — 계산 로직을 중복 구현하지 말 것.
export function computeNextInspectionDueAt(inspectionCycleMonths?: number | null): string | null {
  if (!inspectionCycleMonths || inspectionCycleMonths <= 0) {
    return null;
  }
  const due = new Date();
  due.setMonth(due.getMonth() + inspectionCycleMonths);
  return due.toISOString().slice(0, 10);
}
