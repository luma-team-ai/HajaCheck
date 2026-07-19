import { GOOGLE_OAUTH_PATH, KAKAO_OAUTH_PATH } from '../constants';

export function PersonalLoginTab() {
  const handleKakaoLogin = () => {
    window.location.href = KAKAO_OAUTH_PATH;
  };

  const handleGoogleLogin = () => {
    window.location.href = GOOGLE_OAUTH_PATH;
  };

  return (
    <div className="flex w-full flex-col gap-3">
      <p className="m-0 text-center text-sm text-text-muted">소셜 계정으로 간편 로그인</p>

      {/* 카카오·구글 공식 브랜드 색상 — tokens.css에 대응 토큰 없음(타 오너 자산 미터치),
          Figma 실측값(카카오 #FEE500, 구글 흰 배경+테두리) 그대로 사용 */}
      <button
        type="button"
        className="w-full cursor-pointer rounded-full border-none bg-[#fee500] py-3.5 text-base font-semibold text-[#191919]"
        onClick={handleKakaoLogin}
      >
        카카오로 계속하기
      </button>
      <button
        type="button"
        className="w-full cursor-pointer rounded-full border border-border bg-surface py-3.5 text-base font-semibold text-text-default"
        onClick={handleGoogleLogin}
      >
        Google로 계속하기
      </button>
    </div>
  );
}
