import { useEffect, useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import { getApiErrorMessage } from '../../../shared/api/types';
import { useInviteCode } from '../hooks/useInviteCode';
import { CopyIcon } from './icons/CopyIcon';
import { KeyIcon } from './icons/KeyIcon';

interface InviteCodeModalProps {
  open: boolean;
  onClose: () => void;
}

const COPY_FEEDBACK_MS = 1500;

function formatTimer(seconds: number): string {
  const clamped = Math.max(0, seconds);
  const minutes = Math.floor(clamped / 60);
  const remainder = clamped % 60;
  return `${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`;
}

// 사용자 초대 코드 발급 모달 — Figma "Modal Panel" 디자인 반영.
// 코드는 백엔드(POST /api/admin/invite-codes, #794)가 발급하고 Redis에 TTL로 보관한다 —
// 프론트는 서버가 내려준 ttlSeconds로 카운트다운만 그린다(값 자체를 클라이언트에서 만들지 않는다).
export function InviteCodeModal({ open, onClose }: InviteCodeModalProps) {
  const { issueInviteCode, isIssuing, issueError, revokeInviteCode } = useInviteCode();
  const [code, setCode] = useState('');
  const [secondsLeft, setSecondsLeft] = useState(0);
  const [copied, setCopied] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);

  // 모달이 열릴 때마다 새 코드를 발급받고 타이머를 서버 TTL로 초기화한다.
  useEffect(() => {
    if (!open) {
      return;
    }
    setCode('');
    setCopied(false);
    void issueInviteCode()
      .then((result) => {
        setCode(result.code);
        setSecondsLeft(result.ttlSeconds);
      })
      .catch(() => {});
    // issueInviteCode(mutateAsync)는 매 렌더 새 참조라 의존성에 넣으면 재발급 루프가 된다 — open 전이에만 반응한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  // 모달이 닫힐 때(오버레이 없이 "닫기" 버튼으로만) 아직 안 쓴 코드를 즉시 폐기한다 — TTL 만료를 기다리지 않고
  // 그 자리에서 재사용 창을 없앤다. code가 비어 있으면(발급 실패/응답 전) 호출하지 않는다.
  function handleClose() {
    if (code) {
      revokeInviteCode(code);
    }
    onClose();
  }

  useEffect(() => {
    if (!open || !code || secondsLeft <= 0) {
      return;
    }
    const timer = setInterval(() => {
      setSecondsLeft((prev) => Math.max(0, prev - 1));
    }, 1000);
    return () => clearInterval(timer);
  }, [open, code, secondsLeft]);

  useEffect(() => {
    if (!copied && !copyFailed) {
      return;
    }
    const timer = setTimeout(() => {
      setCopied(false);
      setCopyFailed(false);
    }, COPY_FEEDBACK_MS);
    return () => clearTimeout(timer);
  }, [copied, copyFailed]);

  const isExpired = Boolean(code) && secondsLeft <= 0;

  function handleReissue() {
    setCode('');
    setCopied(false);
    void issueInviteCode()
      .then((result) => {
        setCode(result.code);
        setSecondsLeft(result.ttlSeconds);
      })
      .catch(() => {});
  }

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
    } catch {
      // 권한 거부·비보안 컨텍스트(HTTP)에서 writeText가 reject될 수 있다 — unhandled rejection 대신
      // "복사 실패" 피드백을 잠깐 보여준다(코드는 여전히 화면에 보이니 수동 선택으로 대체 가능).
      setCopyFailed(true);
    }
  }

  return (
    <Modal open={open} onClose={handleClose} closeOnOverlayClick={false}>
      <div className="flex w-100 max-w-full flex-col items-center gap-6 text-center">
        <div className="flex h-14 w-14 items-center justify-center rounded-full bg-surface-muted text-heading">
          <KeyIcon />
        </div>

        <div className="flex flex-col gap-1.5">
          <h2 className="m-0 text-xl font-bold text-heading">사용자 초대 코드 발급</h2>
          <p className="m-0 text-sm text-text-muted">
            신규 사용자를 등록하기 위한 임시 보안 코드를 생성합니다.
          </p>
        </div>

        <div className="flex w-full flex-col items-center gap-2 rounded-[16px] border border-dashed border-border bg-surface-muted px-6 py-6">
          {issueError ? (
            <button
              type="button"
              onClick={handleReissue}
              className="cursor-pointer border-none bg-none text-sm font-semibold text-danger underline"
            >
              {getApiErrorMessage(issueError, '발급에 실패했습니다')} · 다시 시도
            </button>
          ) : isIssuing || !code ? (
            <span className="text-sm text-text-muted">발급 중...</span>
          ) : isExpired ? (
            <button
              type="button"
              onClick={handleReissue}
              className="cursor-pointer border-none bg-none text-lg font-semibold text-text-muted underline"
            >
              코드가 만료되었습니다 · 새로 발급받기
            </button>
          ) : (
            <>
              <span className="text-2xl font-bold tracking-[0.1em] text-heading">{code}</span>
              <span className="flex items-center gap-1.5 text-xs font-medium text-danger">
                <span className="h-1.5 w-1.5 rounded-full bg-danger" aria-hidden />
                유효 시간 {formatTimer(secondsLeft)}
              </span>
            </>
          )}
        </div>

        <div className="flex w-full items-start gap-2 rounded-[12px] bg-surface-muted px-4 py-3 text-left text-xs text-text-muted">
          <span aria-hidden>ⓘ</span>
          <p className="m-0">
            이 코드는 1회용이며 보안을 위해 유효 시간 내에 입력해야 합니다.
            <br />
            시간이 만료되면 새로운 코드를 다시 발급받으세요.
          </p>
        </div>

        <div className="flex w-full gap-3">
          <Button variant="secondary" size="lg" onClick={handleClose} className="flex-1">
            닫기
          </Button>
          <Button
            variant="primary"
            size="lg"
            onClick={() => void handleCopy()}
            disabled={!code || isExpired}
            className="flex-1"
          >
            <CopyIcon />
            {copyFailed ? '복사 실패 · 직접 선택해 주세요' : copied ? '복사됨' : '코드 복사하기'}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
