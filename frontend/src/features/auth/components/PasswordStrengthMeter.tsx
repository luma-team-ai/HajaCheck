import { getPasswordStrength } from '../utils/authFormValidators';

const STRENGTH_LABEL: Record<ReturnType<typeof getPasswordStrength>, string> = {
  weak: '약함',
  medium: '보통',
  strong: '강함',
};

interface PasswordStrengthMeterProps {
  password: string;
}

// 새 비밀번호 설정 화면 강도미터 — 시각 피드백용(제출 가능 여부는 별도 검증기가 판단)
export function PasswordStrengthMeter({ password }: PasswordStrengthMeterProps) {
  if (!password) return null;

  const strength = getPasswordStrength(password);

  return (
    <div className="auth-password-strength" aria-label={`비밀번호 강도: ${STRENGTH_LABEL[strength]}`}>
      <div className={`auth-password-strength-bar auth-password-strength-bar--${strength}`} />
      <span className="auth-password-strength-label">{STRENGTH_LABEL[strength]}</span>
    </div>
  );
}
