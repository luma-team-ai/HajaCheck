# 로컬 spring 컨테이너 빌드 느림 · `Unable to delete directory` 해결

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-28

`docker-compose.override.yml`의 `spring` 서비스 2곳을 수정했습니다. **로컬 개발 전용 파일이라 운영 배포에는 영향이 없습니다.**

## 겪던 증상

1. **빌드가 매우 느림** — clean 빌드 2분 이상, 앱 기동까지 282초
2. **`git pull` 후 컴파일 실패**가 반복
   ```
   java.io.IOException: Unable to delete directory '/workspace/build/classes/java/main'
   Failed to delete some children. This might happen because a process has files open...
   ```
   이후 `build/`가 깨진 채 남아 `Main class name has not been configured` 로 이어짐

## 원인

둘 다 뿌리는 **Windows Docker Desktop 바인드 마운트**입니다. Gradle `build/`는 작은 클래스 파일이 수천 개라, 바인드 마운트가 가장 취약한 워크로드입니다.

실측 (컨테이너 안, 작은 파일 2000개):

| 작업 | 바인드 마운트 | named volume | 차이 |
|---|---|---|---|
| 생성 | 14,231 ms | 79 ms | **180배** |
| 삭제 | 4,318 ms | 31 ms | **139배** |

- **느림** → 파일마다 Windows ↔ 리눅스 VM 경계를 넘음
- **삭제 실패** → Windows는 **열린 파일을 지울 수 없음**. `bootRun` JVM이 `.class`를 연 채로 `classes --continuous`가 그 디렉터리를 지우려다 실패. (리눅스/맥은 열린 파일도 unlink 되므로 이 에러가 없음)

## 수정 내용

### ① `build/`를 named volume으로 분리

```yaml
    volumes:
      - ./backend:/workspace
      - ./docs:/docs:ro
      - gradle-cache:/root/.gradle
      - gradle-build:/workspace/build   # ← 추가
```
하단 `volumes:`에 `gradle-build:` 선언 추가.

빌드 산출물만 리눅스 파일시스템으로 옮깁니다. **소스(`./backend`)는 바인드 마운트 그대로**라 핫리로드는 동일하게 동작합니다.

### ② 컨테이너 시작 명령을 순차 실행으로

```yaml
    command:
      - sh
      - -c
      - |
        ./gradlew classes                  # ① 블로킹 — 풀 컴파일 완료까지 대기
        ./gradlew classes --continuous &   # ② 그 다음 감시 시작
        ./gradlew bootRun                  # ③ 클래스 완비 상태에서 실행
```

기존에는 ②③이 **동시에** 시작해 같은 `build/classes`에 둘 다 풀 컴파일을 쓰다가, `bootRun`이 0바이트 클래스 파일을 읽어 이렇게 죽었습니다:

```
Execution failed for task ':resolveMainClassName'.
> Index 6 out of bounds for length 0
```

(로그에 `> Task :compileJava`가 두 번 찍히는 게 동시 컴파일 증거) 볼륨 도입과 무관하게 **원래 있던 잠재 버그**이며, `build/`가 빈 상태에서 확정적으로 재현됩니다.

## 결과

| | 이전 | 이후 |
|---|---|---|
| 앱 기동 | 282.06초 | **11.61초** |

`Unable to delete directory`, `resolveMainClassName` 에러 모두 재현되지 않음.

## 팀원이 알아야 할 것

**적용 방법** — 레포 루트에서 (⚠️ `backend/`에서 실행하면 루트 `.env`의 `COMPOSE_FILE`이 안 읽혀 공유 dev DB가 아닌 로컬 빈 DB에 붙습니다):

```bash
git pull
docker compose up -d --force-recreate spring
```

`restart`가 아니라 **`--force-recreate`** 여야 새 볼륨이 붙습니다. 이미지는 안 바뀌었으니 `--build`는 불필요합니다.

**최초 1회는 풀 빌드** — 볼륨이 비어있어 처음엔 시간이 걸립니다. 속도 개선 체감은 그다음 증분 빌드부터입니다.

**유일한 트레이드오프** — 호스트(탐색기·IDE)에서 `backend/build/` 내부를 직접 열어볼 수 없습니다. IDE는 자체적으로 로컬 빌드하므로 개발에는 영향이 없고, 레포 내 스크립트·CI 중 이 경로를 읽는 것은 없음을 확인했습니다.

**맥·리눅스 사용자** — 원래 이 문제가 없었지만 볼륨 사용은 무해하며 오히려 약간 빠릅니다.

**빌드 산출물을 완전히 초기화하려면** (기존 `./gradlew clean` 대체):

```bash
docker compose down spring
docker volume rm hajacheck_gradle-build
docker compose up -d spring
```

## 영향 범위

- 변경 파일: `docker-compose.override.yml` **한 개**
- 애플리케이션 코드 변경 **없음**
- `docker-compose.override.yml`은 로컬 개발 전용 — **운영 배포(arm1) 경로에는 포함되지 않음**
