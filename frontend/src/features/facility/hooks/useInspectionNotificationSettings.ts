import { useQuery } from '@tanstack/react-query';
import { facilityApi } from '../api/facilityApi';

// 점검 알림 설정 조회(#540 ③) — GET /api/facilities/{id}/notification-settings.
// 저장한 적 없는 시설물도 서버가 항상 기본값(사전알림 사용/7일전/경과알림 사용, HAJA-498/V21)을 반환하므로
// 화면은 "설정 없음" 상태를 별도로 다룰 필요가 없다.
export const inspectionNotificationSettingsKey = (facilityId: number) =>
  ['facility', 'notification-settings', facilityId] as const;

export function useInspectionNotificationSettings(facilityId: number) {
  return useQuery({
    queryKey: inspectionNotificationSettingsKey(facilityId),
    queryFn: () => facilityApi.getNotificationSettings(facilityId).then((res) => res.data),
  });
}