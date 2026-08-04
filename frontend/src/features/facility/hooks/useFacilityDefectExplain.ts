import { useQuery } from '@tanstack/react-query';
import { facilityAiApi } from '../api/facilityAiApi';

type Params = {
  defectId: number;
  defectType: string;
  grade: string;
  location: string;
  facilityType: string;
  // 시설물 상세 조회(useFacility)가 아직 진행 중인지 — 하자 조회와 병렬로 뜨므로 이 값으로 조회
  // 시점을 늦춘다(아래 facilityType 설명 참고). 부모가 로딩 상태를 안 넘기면(예: 다른 화면에서
  // 재사용) 즉시 조회하도록 기본값 false.
  isFacilityLoading?: boolean;
};

// AI 실패가 하자 상세 페이지의 비-AI 기능(이미지·상태·등급 등)을 막지 않아야 함(React_코드_컨벤션.md §6) —
// 이 훅의 에러는 FacilityDefectAiExplainPanel 내부에서만 폴백 처리한다.
//
// #1350 — location은 필수 조건에서 제외한다. 세부 위치가 아직 안 채워진 하자가 흔해(location이
// 비어있으면 AI 설명 자체가 영구히 빈 화면으로 남는 문제) defectType·grade만 있어도 AI가 유의미한
// 진단을 생성할 수 있으므로 그 둘만 필수로 남긴다. 빈 location은 facilityAiApi가 자리표시자로
// 보정한다(Spring 프록시 DTO가 location을 @NotBlank로 강제 — code-reviewer P1, backend/.../ai/dto/
// DefectExplainRequest.java 확인됨. 빈 문자열을 그대로 보내면 400으로 막혀 결국 "다시 시도" 폴백만
// 남고 목표(위치 미입력 하자도 AI 설명 표시)를 달성하지 못한다).
//
// facilityType은 별도다 — Facility.type은 등록 시 필수 입력(FacilityFormModal 검증)이라 시설물 조회가
// "성공"하면 항상 값이 있다. 문제는 시점: useFacilityDetail·useFacility가 병렬로 뜨므로 하자 조회가
// 먼저 끝나면 facility?.type이 아직 ''(로딩 중)인 채로 조회가 발사될 수 있다(code-reviewer P2 1차) —
// 그래서 이전엔 Boolean(facilityType)으로 막았다. 하지만 시설물 조회 자체가 실패(네트워크 오류·404
// 등)하면 facilityType이 영구히 ''로 남아 조회가 다시는 안 나가는 새로운 빈 화면 경로가 생겼다
// (code-reviewer P2 2차). location과 동일하게 facilityAiApi가 빈 facilityType도 자리표시자로
// 보정하도록 바꿨으므로, 이제는 "값이 채워졌는지"가 아니라 "시설물 조회가 끝났는지"(성공이든 실패든)
// 로만 조회 시점을 늦춘다 — 실패해도 자리표시자로 AI 설명 자체는 계속 시도한다.
export function useFacilityDefectExplain({
  defectId,
  defectType,
  grade,
  location,
  facilityType,
  isFacilityLoading = false,
}: Params) {
  return useQuery({
    queryKey: ['facility-defect-explain', defectId],
    queryFn: () =>
      facilityAiApi
        .getDefectExplanation({ defectId, defectType, grade, location, facilityType })
        .then((res) => res.data),
    retry: 1,
    enabled: Boolean(defectId) && Boolean(defectType) && Boolean(grade) && !isFacilityLoading,
  });
}