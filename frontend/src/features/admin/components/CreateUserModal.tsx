import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { doPasswordsMatch, isValidEmail, isValidPassword } from '../../auth/utils/authFormValidators';
import { useEmailAvailability } from '../../auth/hooks/useEmailAvailability';
import { EmailDomainField } from '../../auth/components/EmailDomainField';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import {
  ADMIN_FORM_ERROR_CLASS,
  ADMIN_FORM_INLINE_BTN_CLASS,
  ADMIN_FORM_INPUT_CLASS,
  ADMIN_FORM_LABEL_CLASS,
  ADMIN_FORM_SUCCESS_CLASS,
} from '../adminFormClasses';
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
  const [emailLocal, setEmailLocal] = useState('');
  const [emailDomain, setEmailDomain] = useState('');
  // CompanySignupPage와 동일 기본값 — 직접입력이 기본이라 기존 자유입력 동작과 다르지 않다.
  const [isCustomDomain, setIsCustomDomain] = useState(true);
  const [lastCustomDomain, setLastCustomDomain] = useState('');
  const isCustomDomainRef = useRef(true);
  const [password, setPassword] = useState('');
  const [passwordConfirm, setPasswordConfirm] = useState('');
  const [name, setName] = useState('');
  const [role, setRole] = useState<AdminUserRole>('USER');
  const [touched, setTouched] = useState(false);

  const {
    checkEmailAvailability,
    isPending: isCheckingEmail,
    result: emailCheckResult,
    reset: resetEmailCheck,
  } = useEmailAvailability();

  // 로컬파트 + '@' + 도메인 조합 — CompanySignupPage와 동일 파생값(#417, EmailDomainField).
  const email = `${emailLocal.trim()}@${emailDomain.trim()}`;
  const emailValid = isValidEmail(email);
  // 중복확인을 실제로 통과("사용 가능")해야만 등록 가능 — 확인을 안 했거나(undefined) 중복(false)이면 막는다.
  // CompanySignupPage는 진위확인 등 다른 게이트로 이미 신중한 제출을 유도하지만, 이 모달은 관리자가
  // 빠르게 등록하다 중복 이메일로 실패(서버 409)하는 것을 사전에 막기 위해 이 화면만 더 엄격하게 요구한다.
  const emailChecked = emailCheckResult?.available === true;
  const passwordValid = isValidPassword(password);
  const passwordMatch = doPasswordsMatch(password, passwordConfirm);
  const nameValid = name.trim().length > 0;
  const formValid = emailValid && emailChecked && passwordValid && passwordMatch && nameValid;

  // 이메일을 바꾸면 이전 중복확인 결과(stale)를 즉시 무효화 — CompanySignupPage와 동일 패턴.
  function handleEmailLocalChange(value: string) {
    setEmailLocal(value);
    if (emailCheckResult) resetEmailCheck();
  }

  function handleEmailDomainChange(value: string) {
    setEmailDomain(value);
    if (isCustomDomainRef.current) setLastCustomDomain(value);
    if (emailCheckResult) resetEmailCheck();
  }

  function handleEmailCustomModeChange(isCustom: boolean) {
    isCustomDomainRef.current = isCustom;
    setIsCustomDomain(isCustom);
    if (isCustom) setEmailDomain(lastCustomDomain);
    if (emailCheckResult) resetEmailCheck();
  }

  function handleCheckEmail() {
    if (!emailValid) return;
    checkEmailAvailability(email.trim());
  }

  function resetForm() {
    setEmailLocal('');
    setEmailDomain('');
    setIsCustomDomain(true);
    isCustomDomainRef.current = true;
    setLastCustomDomain('');
    setPassword('');
    setPasswordConfirm('');
    setName('');
    setRole('USER');
    setTouched(false);
    resetEmailCheck();
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
    <Modal open={open} onClose={handleClose} title="사용자 등록" closeOnOverlayClick={false}>
      <form onSubmit={handleSubmit} className="flex w-105 max-w-full flex-col gap-6">
        <div className="flex flex-col gap-2">
          <label htmlFor="create-user-email" className={ADMIN_FORM_LABEL_CLASS}>
            이메일
          </label>
          <EmailDomainField
            id="create-user-email"
            localPart={emailLocal}
            domain={emailDomain}
            isCustomDomain={isCustomDomain}
            onLocalPartChange={handleEmailLocalChange}
            onDomainChange={handleEmailDomainChange}
            onCustomModeChange={handleEmailCustomModeChange}
          />
          <button
            type="button"
            className={ADMIN_FORM_INLINE_BTN_CLASS}
            onClick={handleCheckEmail}
            disabled={isCheckingEmail || !emailValid}
          >
            중복확인
          </button>
          {emailCheckResult && (
            <p className={emailCheckResult.available ? ADMIN_FORM_SUCCESS_CLASS : ADMIN_FORM_ERROR_CLASS}>
              {emailCheckResult.available ? '사용 가능한 이메일입니다.' : '이미 가입된 이메일입니다.'}
            </p>
          )}
          {(emailLocal.length > 0 || emailDomain.length > 0 || touched) && !emailValid && (
            <p className="m-0 text-xs text-danger">이메일 형식이 올바르지 않습니다.</p>
          )}
          {touched && emailValid && !emailCheckResult && (
            <p className="m-0 text-xs text-danger">이메일 중복확인을 완료해 주세요.</p>
          )}
        </div>

        <div className="flex flex-col gap-2">
          <label htmlFor="create-user-password" className={ADMIN_FORM_LABEL_CLASS}>
            비밀번호
          </label>
          <input
            id="create-user-password"
            type="password"
            className={ADMIN_FORM_INPUT_CLASS}
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
          <label htmlFor="create-user-password-confirm" className={ADMIN_FORM_LABEL_CLASS}>
            비밀번호 재확인
          </label>
          <input
            id="create-user-password-confirm"
            type="password"
            className={ADMIN_FORM_INPUT_CLASS}
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
          <label htmlFor="create-user-name" className={ADMIN_FORM_LABEL_CLASS}>
            이름
          </label>
          <input
            id="create-user-name"
            type="text"
            className={ADMIN_FORM_INPUT_CLASS}
            placeholder="실명 입력"
            value={name}
            onChange={(event) => setName(event.target.value)}
            autoComplete="off"
          />
          {touched && !nameValid && <p className="m-0 text-xs text-danger">이름은 필수입니다.</p>}
        </div>

        <div className="flex flex-col gap-2">
          <label htmlFor="create-user-role" className={ADMIN_FORM_LABEL_CLASS}>
            역할
          </label>
          <select
            id="create-user-role"
            className={ADMIN_FORM_INPUT_CLASS}
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
