import { useQuery } from '@tanstack/react-query';
import { facilityAiApi } from '../api/facilityAiApi';

type Params = {
  defectId: number;
  defectType: string;
  grade: string;
  location: string;
  facilityType: string;
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
// 끝나면 항상 값이 있다. 문제는 시점: useFacilityDetail·useFacility가 병렬로 뜨므로 하자 조회가 먼저
// 끝나면 facility?.type이 아직 ''(로딩 중)인 채로 조회가 발사될 수 있다(code-reviewer P2). facilityType이
// queryKey에 없어 나중에 값이 채워져도 자동 재조회되지 않으므로, location과 달리 자리표시자로
// 메꾸지 않고 enabled에서 값이 채워질 때까지 대기시킨다.
export function useFacilityDefectExplain({ defectId, defectType, grade, location, facilityType }: Params) {
  return useQuery({
    queryKey: ['facility-defect-explain', defectId],
    queryFn: () =>
      facilityAiApi
        .getDefectExplanation({ defectId, defectType, grade, location, facilityType })
        .then((res) => res.data),
    retry: 1,
    enabled: Boolean(defectId) && Boolean(defectType) && Boolean(grade) && Boolean(facilityType),
  });
}