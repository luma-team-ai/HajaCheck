import { useMutation } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import type { InviteCodeIssueResult } from '../api/adminApi';
import { adminApi } from '../api/adminApi';

// 초대 코드(#794)는 목록에 반영되는 자원이 아니라 발급 모달 안에서만 쓰고 버리는 값이라
// react-query 캐시 무효화 대상이 없다 — useCreateUser와 달리 onSuccess 훅이 필요 없다.
export function useInviteCode() {
  const issueMutation = useMutation<InviteCodeIssueResult, ApiError, void>({
    mutationFn: () => adminApi.issueInviteCode().then((res) => res.data),
  });
  const revokeMutation = useMutation<void, ApiError, string>({
    mutationFn: (code) => adminApi.deleteInviteCode(code).then(() => undefined),
  });

  return {
    issueInviteCode: issueMutation.mutateAsync,
    isIssuing: issueMutation.isPending,
    issueError: issueMutation.error,
    revokeInviteCode: revokeMutation.mutate,
  };
}
