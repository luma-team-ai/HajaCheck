// eslint 9 flat config — 최소 구성 (규칙 강화는 코어 플랫폼 챕터에서)
import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist'] },
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
);
