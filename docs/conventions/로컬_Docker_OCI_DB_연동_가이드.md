# hajaCheck — 로컬 Docker에서 OCI 공용 개발 DB 쓰기

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-17 · 이전 버전 `archive/`

> 대상: `docker compose`로 spring/fastapi/nginx를 띄우되, 로컬 postgres/redis 컨테이너 대신
> **OCI 공용 개발 DB**(팀 공용 서버)에 SSH 터널로 붙어서 개발하고 싶은 팀원.
> 기본 로컬 흐름(로컬 자체 postgres/redis 컨테이너)은 `로컬_개발_가이드.md`를 그대로 따르면 되고,
> 이 문서는 그 대안(OCI DB 연동) 흐름만 다룬다.
> 연관 문서: `로컬_개발_가이드.md`, 루트 `docker-compose.yml`/`docker-compose.override.yml`/`docker-compose.oci-db.yml`

---

## 1. 구조 요약

- `docker-compose.yml`(base)은 로컬·운영 공용이라 직접 안 건드림.
- `docker-compose.override.yml`은 `-f` 없이 `docker compose up` 시 자동 병합되는 로컬 개발용 레이어(핫리로드 등) — 그대로 유지.
- `docker-compose.oci-db.yml`은 **옵트인 오버레이**. `-f`로 명시할 때만 적용되며, 로컬 postgres/redis 컨테이너 대신
  `db-tunnel`(autossh 사이드카)이 OCI DB로 SSH 터널을 열고, spring/fastapi가 그 터널을 통해 OCI의 실제
  PostgreSQL/Redis에 붙는다.

## 2. 넘겨받아야 할 파일 / 이미 저장소에 있는 파일

| 파일 | 어디서 오나 | 비고 |
|---|---|---|
| `docker-compose.yml`, `docker-compose.override.yml` | 저장소에 이미 있음(`git pull`) | 손댈 필요 없음 |
| `docker-compose.oci-db.yml` | 저장소 루트에 커밋되어 있음(`git pull`로 받음) — 시크릿 없이 `${VAR}` 플레이스홀더만 있어 커밋해도 안전 | 실제 값은 전부 `.env`에서 주입 |
| `.env` | **각자 개인적으로 직접 채운다** — 파일 자체를 주고받지 않음(시크릿 포함) | 3번 항목 값 채우기 |
| OCI SSH 개인키(`~/.ssh/hajacheck` 등) | **본인 몫으로 인프라 담당자에게 신규 발급 요청** — 남의 개인키를 복사해서 쓰지 않는다 | 계정별 접근 이력 추적을 위해 개인키 공유 금지 |
| `~/.ssh/config`의 `Host hajacheck-db` 항목 | 인프라 담당자에게 본인 계정 정보(HostName/User/Port)를 받아 본인 `~/.ssh/config`에 직접 추가 | 기존 host-native `ssh -N hajacheck-db` 워크플로우와 동일 |

즉 실제로 "넘겨줘야 하는" 건 파일이 아니라 **① OCI 서버 접속 권한(개인 SSH 키 발급) ② `.env`에 채울 시크릿 값 목록**입니다. `docker-compose.oci-db.yml`은 이미 `git pull`로 받아집니다.

## 3. `.env`에 채워야 할 항목

```bash
# 기존에 이미 있어야 하는 값(로컬_개발_가이드.md 공통) — 실제 값은 팀 공유 채널 참고, 여기 적지 말 것
DB_PASSWORD=<본인 .env의 기존 값>
REDIS_PASSWORD=<본인 .env의 기존 값>

# OCI SSH 터널용 (신규)
OCI_SSH_HOST=<OCI 서버 IP/도메인>          # 인프라 담당자에게 문의
OCI_SSH_USER=<본인 SSH 계정>                # 계정별로 발급받은 값
OCI_SSH_KEY_PATH=<본인 개인키 절대경로>      # 예: ~/.ssh/hajacheck
OCI_DB_REMOTE_PORT=5432
OCI_REDIS_REMOTE_PORT=6380                  # ⚠️ 6379 아님 — sshd PermitOpen이 정확한 목적지 포트를 요구함

# 소셜 로그인(카카오/구글)도 로컬에서 테스트하려면 추가(선택) — 팀이 이미 등록해둔 앱 값을 공유받아 채운다
KAKAO_CLIENT_ID=<팀 공유 카카오 앱 REST API 키>
KAKAO_CLIENT_SECRET=<팀 공유 카카오 앱 시크릿>
GOOGLE_CLIENT_ID=<팀 공유 구글 OAuth 클라이언트 ID>
GOOGLE_CLIENT_SECRET=<팀 공유 구글 OAuth 클라이언트 시크릿>
```

카카오/구글 값은 **팀이 이미 등록해둔 OAuth 앱의 client-id/secret**을 그대로 공유받아 쓰면 되고(새 앱 생성 불필요),
`docker-compose.oci-db.yml`의 spring env는 redirect-uri를 `http://localhost/login/oauth2/code/{kakao,google}`으로
**고정 문자열 override**해뒀다(⚠️ `{baseUrl}` 템플릿이 아니라 리터럴 고정값 — 포트 번호가 없다는 점 주의). 이 오버레이는
`nginx`가 80번으로 서빙하는 단일 오리진 접속(`http://localhost`)을 전제로 하므로, **`:8080`(spring 직접 접근)이나
`:5173`(frontend-dev 직접 접근)으로 로그인 진입 시 카카오 콘솔 값과 실제 redirect_uri가 달라 KOE006
(등록되지 않은 Redirect URI)이 난다.** 반드시 `http://localhost`(포트 생략, nginx 경유)로 접속해서 로그인을
시작해야 한다. 카카오/구글 콘솔의 Redirect URI 목록에는 `http://localhost/login/oauth2/code/{kakao,google}`이
리터럴로 등록되어 있어야 한다(한 번만 등록해두면 팀원 전체가 공용으로 씀 — 팀원별로 매번 추가할 필요 없음).

## 4. 실행

```bash
docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.oci-db.yml \
  up --build spring fastapi nginx db-tunnel frontend-dev
```
(PowerShell도 위와 동일 — 한 줄로 이어 쓰면 됨: `docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.oci-db.yml up --build spring fastapi nginx db-tunnel frontend-dev`)

> ✅ **권장 — `.env`에 `COMPOSE_FILE`을 설정하면 `-f` 나열이 필요 없다**(#302):
> ```
> COMPOSE_FILE=docker-compose.yml:docker-compose.override.yml:docker-compose.oci-db.yml
> ```
> 이후로는 `docker compose up -d --build`만 쳐도 3개가 항상 적용된다.
> **`-f`를 매번 나열하는 방식은 한 번만 빠뜨려도 오버레이 없이 컨테이너가 재생성돼 로그인이 조용히 깨진다**(§5 표 참조).
> `-f`를 명시하면 `override.yml` **자동 병합이 꺼진다**는 점도 주의 — 자동 병합은 `-f` 미지정일 때만 동작하므로, `-f`를 쓸 땐 `override.yml`도 반드시 나열해야 한다(빼면 `frontend-dev has neither an image nor a build context specified`).

- `postgres`/`redis` 서비스명을 목록에서 뺐기 때문에 로컬 컨테이너는 안 뜨고, `db-tunnel`이 대신 OCI로 붙는다.
- `db-tunnel`이 헬스체크(5432·6380 포트 확인)를 통과해야 `spring`/`fastapi`가 기동을 시작한다.
- **서비스명을 나열하는 방식**이라 `frontend-dev`를 빼먹으면(override.yml에 정의돼 있어도) 안 뜬다 — 핫리로드 Vite dev 서버(`:5173`)로 확인하려면 반드시 포함. 서비스명 없이 `up --build`만 실행하면(-f 3개는 유지) override.yml에 정의된 서비스 전체가 다 뜬다.
- 로그인 테스트는 `:5173`이 아니라 **`http://localhost`(nginx, 80번)** 로 접속해서 시작할 것 — 위 §3 redirect-uri 설명 참고.
  **소셜 로그인은 `80`에서 시작해 `80`으로 끝난다**(`APP_OAUTH2_SUCCESS_REDIRECT`가 `http://localhost/dashboard`).
  이 오버레이는 **nginx 80 단일 오리진**이 전제라 콜백(`redirect-uri`)과 착지(`success-redirect`)가 **같은 오리진**이어야 한다 — 운영(`application-docker.yml`)도 둘 다 `luma200ok.com`으로 같다.
  > ⚠️ 2026-07-17 이전엔 `success-redirect`만 `:5173`이라 **80에서 로그인하면 5173으로 튕겼다**(#302).
  > 80은 nginx가 서빙하는 **dist**(프로덕션 빌드 → `DEV=false` → MSW 없음), 5173은 **vite dev 서버**(`DEV=true` → MSW 기본 켜짐)라 **서로 다른 앱**이다.
  > 그래서 80에서 로그인해도 5173으로 착지해 목 `getMe`(항상 401)에 걸려 `/login`으로 축출됐다(아래 §5 표의 "튕김" 항목).

## 5. 자주 걸리는 것 (이번에 실제로 겪은 이슈)

| 증상 | 원인 / 해결 |
|---|---|
| `pull access denied for linuxserver/openssh-client` | 그 이미지가 실제로 존재하지 않음 — `docker-compose.oci-db.yml`은 `alpine` 기반 인라인 빌드로 이미 고쳐져 있으니 최신 버전인지 확인 |
| `chmod: /root/.ssh/id_tunnel: Read-only file system` | 개인키를 `:ro`로 마운트해놓고 그 자리에서 chmod하려 해서 발생 — 현재 파일은 `/run/secrets`에 마운트 후 컨테이너 내부로 복사한 뒤 chmod하도록 이미 수정됨 |
| `channel ... open failed: administratively prohibited` | OCI sshd의 `PermitOpen`이 목적지 포트를 엄격히 검사함. `~/.ssh/config`의 `LocalForward` 포트(특히 Redis 6380, 6379 아님)와 `.env`의 `OCI_*_REMOTE_PORT`가 정확히 일치하는지 확인 |
| `RedisConnectionFailureException` (Postgres는 되는데 Redis만 실패) | `db-tunnel` 헬스체크가 5432만 확인해서 6380 포워딩 실패를 못 잡아냄 — 지금은 healthcheck가 둘 다 확인하도록 고쳐짐. 그래도 안 되면 `.env`의 `REDIS_PASSWORD`가 OCI 공용 Redis의 실제 비밀번호와 일치하는지 확인 |
| `SchemaManagementException: missing column ...` | 공용 OCI DB 스키마가 최신 엔티티와 안 맞음(Flyway 없이 `ddl-auto`로만 관리되는 프로젝트라 드리프트 발생 가능). 팀에 공유해서 DB 쪽 컬럼을 맞추거나, 본인 담당 기능이 아니면 일단 그 기능만 피해서 테스트 |
| 카카오/구글 로그인 "액세스 차단됨: 승인 오류" | `docker` 프로파일(`application-docker.yml`)이 운영 도메인으로 redirect-uri를 하드코딩해서 로컬에선 원래 안 됨. `docker-compose.oci-db.yml`의 spring env로 redirect-uri·세션 쿠키·리다이렉트 대상을 로컬용으로 override해뒀으니, 3번 항목의 카카오/구글 값만 채우면 동작함 |
| 카카오 로그인 KOE006(등록되지 않은 Redirect URI) | `:8080`이나 `:5173`으로 직접 접속해서 로그인을 시작한 경우 — redirect-uri override가 `http://localhost`(포트 없음, nginx 80 전제) 고정값이라 다른 포트로 접속하면 실제 redirect_uri와 콘솔 등록값이 달라짐. `http://localhost`(nginx)로 접속해서 로그인 시작할 것 |
| 소셜 로그인 성공(DB에 유저 생성됨)했는데 `/dashboard` 잠깐 보이다 바로 `/login`으로 튕김 | 세션 문제가 아니라 **MSW(Mock Service Worker)가 `GET /api/users/me`를 세션과 무관하게 항상 401로 응답**하기 때문(`frontend/src/features/auth/api/authApi.handlers.ts`). `frontend-dev`가 `VITE_ENABLE_MSW`를 안 켜면 기본값(DEV=true)이 켜짐 상태라 실 백엔드 세션이 가려짐. `docker-compose.oci-db.yml`의 `frontend-dev` 환경변수에 `VITE_ENABLE_MSW: "false"`가 반영돼 있다. **화면 좌하단에 `MSW 목 모드` 배지가 보이면 목이 켜진 것**(#302) — 아래 두 경우를 확인할 것:<br>① **컨테이너가 오버레이 없이 떴다** — `-f`를 하나라도 명시하면 `override.yml` 자동 병합이 꺼지므로 정식 커맨드는 3개를 전부 나열한다. 그렇게 띄운 뒤 무심코 `docker compose up -d`(-f 없이)를 돌리면 `oci-db.yml`이 빠진 채 재생성돼 `frontend-dev`만 이 값을 잃는다(**부분 오염** — 컨테이너는 전부 healthy로 보임). `docker inspect <컨테이너> --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}'`로 `oci-db.yml` 포함 여부 확인. **`.env`에 `COMPOSE_FILE`을 설정하면 원천 차단**(`.env.example` 참조).<br>② **`:5173`으로 착지했다** — 2026-07-17 이전 `success-redirect`가 `:5173`이라 80에서 로그인해도 vite dev 서버(MSW 켜짐)로 넘어갔다. 지금은 `http://localhost/dashboard`로 고쳐져 80에 머문다 |
| 소셜 로그인 후 주소가 `localhost` → `localhost:5173`으로 바뀜 | **2026-07-17 이전 버그**(#302) — `redirect-uri`는 80인데 `success-redirect`만 `:5173`이라 다른 오리진(=다른 앱)으로 착지했다. 쿠키는 포트를 구분하지 않아 로그인 자체는 유지되는 탓에 눈치채기 어려웠고, **80으로 dist를 검증하려던 목적도 무너졌다**(로그인 후엔 dev 서버를 보게 됨). 지금은 둘 다 `http://localhost`로 통일. 그래도 포트가 바뀐다면 컨테이너가 옛 설정으로 떠 있는 것이니 `--force-recreate` |
| `frontend-dev` 컨테이너가 "Created" 상태로 멈춰 안 뜸(`ports are not available: ... 0.0.0.0:5173`) | 호스트에서 이미 `npm run dev`(로컬 네이티브 Vite) 등이 5173을 점유 중. `netstat -ano \| findstr :5173`(PowerShell은 `Get-NetTCPConnection -LocalPort 5173`)로 PID 확인 후 종료하거나, 로컬 dev 서버를 끄고 `docker start`/`up`을 다시 실행 |
| `docker-compose.oci-db.yml` 적용 시 파싱 오류로 기동 자체가 실패 | `depends_on`의 `postgres: !reset null` 같은 `!reset` 태그는 Docker Compose v2.20+ 에서 지원하는 확장 문법 — 그보다 낮은 버전이면 파싱 오류가 난다. `docker compose version`으로 버전 확인 후 업데이트 |

## 6. 주의사항

- **OCI DB는 팀 공용 개발 서버다.** `app.local-seed.enabled`를 여기서 `true`로 켜지 말 것(known-password ADMIN 계정이 공유 DB에 그대로 생성됨).
- 본인 SSH 개인키는 절대 다른 사람과 공유하거나 저장소에 커밋하지 않는다.
- `db-tunnel`은 `StrictHostKeyChecking=accept-new`로 붙는다 — known_hosts는 named volume(`db-tunnel-ssh`)에
  지속되어 2회차부터는 호스트 키가 고정되지만, **볼륨이 비어있는 최초 1회 접속은 키를 검증 없이 신뢰(TOFU)**한다.
  강화하려면 최초 기동 전 인프라 담당자에게 OCI 서버 호스트 키 지문을 받아
  `docker compose ... run --rm db-tunnel ssh-keyscan -H <OCI_SSH_HOST>` 결과와 대조할 것(선택).
- `REDIS_PASSWORD`는 `docker-compose.oci-db.yml`에서 `redis://:${REDIS_PASSWORD}@...` 형태로 URL에 그대로 삽입된다 — 비밀번호에 `@`·`:`·`/`·`%` 같은 URL 예약 문자가 들어있으면 파싱이 깨질 수 있으니 피할 것.
