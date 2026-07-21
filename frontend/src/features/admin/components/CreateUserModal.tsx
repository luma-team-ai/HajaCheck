import { useState } from 'react';
import type { FormEvent } from 'react';
import { doPasswordsMatch, isValidEmail, isValidPassword } from '../../auth/utils/authFormValidators';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import { ROLE_CHANGE_OPTIONS, ROLE_LABEL } from '../constants';
import type { AdminUserRole } from '../types';

interface CreateUserModalProps {
  open: boolean;
  onClose: () => void;
  onConfirm: (input: {
    email: string;
    password: string;
    name: string;
    role: AdminUserRole;
  }) => Promise<void>;
  isSubmitting: boolean;
  submitErrorMessage?: string;
}

const INPUT_CLASS =
  'w-full rounded-full border border-border bg-surface px-4 py-3 text-sm text-text-default placeholder:text-text-muted focus:outline-none focus-visible:ring-1 focus-visible:ring-primary';
const LABEL_CLASS = 'text-xs font-medium tracking-wide text-text-muted';

// 사용자 등록 모달 — Figma node-id 1147-2649. "사용자 초대" 버튼을 대체하며, 회원가입 폼과 같은
// 검증 정규식(authFormValidators)을 재사용한다 — 비밀번호 확인 일치 여부는 클라이언트에서만
// 검사하고 서버로는 보내지 않는다(CompanySignupRequest와 동일한 트레이드오프).
export function CreateUserModal({
  open,
  onClose,
  onConfirm,
  isSubmitting,
  submitErrorMessage,
}: CreateUserModalProps) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [passwordConfirm, setPasswordConfirm] = useState('');
  const [name, setName] = useState('');
  const [role, setRole] = useState<AdminUserRole>('USER');
  const [touched, setTouched] = useState(false);

  const emailValid = isValidEmail(email);
  const passwordValid = isValidPassword(password);
  const passwordMatch = doPasswordsMatch(password, passwordConfirm);
  const nameValid = name.trim().length > 0;
  const formValid = emailValid && passwordValid && passwordMatch && nameValid;

  function resetForm() {
    setEmail('');
    setPassword('');
    setPasswordConfirm('');
    setName('');
    setRole('USER');
    setTouched(false);
  }

  function handleClose() {
    resetForm();
    onClose();
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setTouched(true);
    if (!formValid) {
      return;
    }
    // catch만 해서 콘솔에 unhandled rejection이 찍히지 않게 한다 — 실패 메시지는
    // submitErrorMessage(mutation.error)로 아래에 표시된다(다른 관리자 모달과 동일 패턴).
    onConfirm({ email: email.trim(), password, name: name.trim(), role })
      .then(resetForm)
      .catch(() => {});
  }

  return (
    <Modal open={open} onClose={handleClose} title="사용자 등록">
      <form onSubmit={handleSubmit} className="flex w-105 max-w-full flex-col gap-6">
        <div className="flex flex-col gap-2">
          <label htmlFor="create-user-email" className={LABEL_CLASS}>
            이메일
          </label>
          <input
            id="create-user-email"
            type="email"
            className={INPUT_CLASS}
            placeholder="아이디 입력"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            autoComplete="off"
          />
          {touched && !emailValid && (
            <p className="m-0 text-xs text-danger">이메일 형식이 올바르지 않습니다.</p>
          )}
        </div>

        <div className="flex flex-col gap-2">
          <label htmlFor="create-user-password" className={LABEL_CLASS}>
            비밀번호
          </label>
          <input
            id="create-user-password"
            type="password"
            className={INPUT_CLASS}
            placeholder="비밀번호 입력"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="new-password"
          />
          {touched && !passwordValid && (
            <p className="m-0 text-xs text-danger">비밀번호는 8자 이상, 영문+숫자를 포함해야 합니다.</p>
          )}
        </div>

        <div className="flex flex-col gap-2">
          <label htmlFor="create-user-password-confirm" className={LABEL_CLASS}>
            비밀번호 재확인
          </label>
          <input
            id="create-user-password-confirm"
            type="password"
            className={INPUT_CLASS}
            placeholder="비밀번호 입력"
            value={passwordConfirm}
            onChange={(event) => setPasswordConfirm(event.target.value)}
            autoComplete="new-password"
          />
          {touched && !passwordMatch && (
            <p className="m-0 text-xs text-danger">비밀번호가 일치하지 않습니다.</p>
          )}
        </div>

        <div className="flex flex-col gap-2">
          <label htmlFor="create-user-name" className={LABEL_CLASS}>
            이름
          </label>
          <input
            id="create-user-name"
            type="text"
            className={INPUT_CLASS}
            placeholder="실명 입력"
            value={name}
            onChange={(event) => setName(event.target.value)}
            autoComplete="off"
          />
          {touched && !nameValid && <p className="m-0 text-xs text-danger">이름은 필수입니다.</p>}
        </div>

        <div className="flex flex-col gap-2">
          <label htmlFor="create-user-role" className={LABEL_CLASS}>
            역할
          </label>
          <select
            id="create-user-role"
            className={INPUT_CLASS}
            value={role}
            onChange={(event) => setRole(event.target.value as AdminUserRole)}
          >
            {ROLE_CHANGE_OPTIONS.map(({ role: option }) => (
              <option key={option} value={option}>
                {ROLE_LABEL[option]}
              </option>
            ))}
          </select>
        </div>

        {submitErrorMessage && (
          <p role="alert" className="m-0 text-sm text-danger">
            {submitErrorMessage}
          </p>
        )}

        <div className="-mx-6 -mb-6 flex justify-center gap-3.5 border-t border-border bg-surface-muted px-6 pt-5 pb-6">
          <Button
            type="button"
            variant="secondary"
            size="lg"
            onClick={handleClose}
            disabled={isSubmitting}
            className="w-[180px]"
          >
            취소
          </Button>
          <Button type="submit" variant="primary" size="lg" disabled={isSubmitting} className="flex-1">
            {isSubmitting ? '등록 중...' : '사용자 등록'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
