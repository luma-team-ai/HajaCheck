// eslint 9 flat config — 최소 구성 (규칙 강화는 코어 플랫폼 챕터에서)
import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import jsxA11y from 'eslint-plugin-jsx-a11y';

export default tseslint.config(
  { ignores: ['dist'] },
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  {
    plugins: { 'react-hooks': reactHooks },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
    },
  },
  {
    // jsx-a11y recommended 룰 도입 (#687). 기존 코드베이스 전반(다른 팀원
    // 소유 feature 포함)에 위반이 다수 존재해 error로 켜면 빌드/CI가
    // 깨지므로, 게이트는 만들되 전부 warn으로 낮춰 점진 정리한다.
    // 각 feature 소유자가 자기 파일의 warning을 정리해 나간다.
    ...jsxA11y.flatConfigs.recommended,
    rules: Object.fromEntries(
      Object.keys(jsxA11y.flatConfigs.recommended.rules).map((rule) => [rule, 'warn']),
    ),
  },
);
