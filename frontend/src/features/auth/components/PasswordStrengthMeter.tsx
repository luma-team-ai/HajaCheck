import type { PasswordStrength } from '../utils/authFormValidators';

// 비밀번호 강도미터(#414, Figma #32) — 위험/보통/안전을 3칸 막대 + 라벨로 안내한다.
// 색은 tokens.css에 대응 토큰이 없어(위험만 --color-danger 존재) auth 로컬 상수로 둔다:
// 위험=#d92d20(=--color-danger), 보통=#f79009(앰버, 대응 토큰 없음), 안전=#1a9a52(auth 성공색,
// formClasses.SUCCESS_CLASSES와 동일 값). tokens.css는 타 오너 자산이라 미터치(#292 선례와 동일 근거).
// 빈 칸은 --color-border 토큰(bg-border)을 그대로 쓴다.
const STRENGTH_META: Record<PasswordStrength, { label: string; color: string; filled: number }> = {
  weak: { label: '위험', color: '#d92d20', filled: 1 },
  medium: { label: '보통', color: '#f79009', filled: 2 },
  strong: { label: '안전', color: '#1a9a52', filled: 3 },
};

interface PasswordStrengthMeterProps {
  strength: PasswordStrength | null;
}

export function PasswordStrengthMeter({ strength }: PasswordStrengthMeterProps) {
  if (!strength) return null;
  const { label, color, filled } = STRENGTH_META[strength];

  return (
    <div className="flex items-center gap-2" aria-live="polite">
      <div className="flex flex-1 gap-1" aria-hidden="true">
        {[0, 1, 2].map((index) => (
          <span
            key={index}
            className={`h-1 flex-1 rounded-full ${index < filled ? '' : 'bg-border'}`}
            style={index < filled ? { backgroundColor: color } : undefined}
          />
        ))}
      </div>
      <span className="shrink-0 text-xs font-medium" style={{ color }}>
        비밀번호 안전도: {label}
      </span>
    </div>
  );
}
