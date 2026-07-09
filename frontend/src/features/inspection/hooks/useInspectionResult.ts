import { useQuery } from '@tanstack/react-query';
import { inspectionApi } from '../api/inspectionApi';

export function useInspectionResult(inspectionId: number) {
  return useQuery({
    queryKey: ['inspection', inspectionId, 'result'],
    queryFn: () => inspectionApi.getResult(inspectionId).then((res) => res.data),
  });
}
