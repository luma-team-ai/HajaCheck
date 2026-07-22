import { useQuery } from '@tanstack/react-query';
import { defectApi } from '../api/defectApi';
import { defectKeys } from './useDefects';

// id가 아직 없을 때(라우트 파라미터 파싱 전)는 요청을 보내지 않는다(enabled: false).
export function useDefect(id: number | undefined) {
  return useQuery({
    queryKey: defectKeys.detail(id ?? -1),
    queryFn: () => defectApi.getDetail(id as number).then((res) => res.data),
    enabled: id != null && !Number.isNaN(id),
  });
}
