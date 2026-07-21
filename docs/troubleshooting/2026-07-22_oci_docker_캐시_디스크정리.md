# 트러블슈팅 — OCI 프로덕션 호스트(arm1) 디스크 압박: docker 캐시 누적 (2026-07-22)

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-22 · **작성:** 인프라 운영 세션

arm1(`oci-arm1`)은 HajaCheck 프로덕션 앱 컨테이너(spring·frontend·fastapi·postgres·redis)를 돌리는 **프로덕션 호스트**다. 같은 호스트에서 PR머신의 docker verify도 돌아, 검수용 이미지 빌드가 쌓이며 디스크를 압박했다. 프로덕션 호스트의 디스크가 차면 앱 동작·배포에 영향이 갈 수 있어 인프라 관점에서 기록한다. 관련: PR머신 트러블슈팅 `pr_machine/docs/troubleshooting/2026-07-22-oci-docker-cache-disk.md`, 메모리 `pr-machine-oci-deploy`.

---

## 1. [운영] 디스크 78% — docker 빌드캐시 49.7GB 누적

**상황**: arm1 루트 디스크(133G)가 **78% 사용(30G 남음)**까지 참. 원인 추적 결과 docker **빌드캐시가 49.7GB**(이미지 21.45GB 별도)까지 누적.

**원인**:
- **빌드캐시 누적(급증분)**: PR머신 docker verify가 PR마다 HajaCheck 이미지를 새로 빌드하며 매번 다른 HEAD sha로 캐시 레이어를 쌓음(~5GB/일). docker는 빌드캐시를 **자동으로 안 지운다** → 무한 증식.
- **이미지(기본분)**: fastapi(torch 포함) 8.9GB 등 21GB는 프로덕션 앱이 쓰는 이미지라 필요분(급증과 무관).

**해결**:
```bash
docker builder prune -a -f    # 현재 이미지가 안 쓰는 캐시 전부 제거 → 46GB 회수
```
→ 디스크 **78% → 49%**(69G 남음). 실행 중 프로덕션 컨테이너·이미지는 무손상(빌드캐시만 제거).

**재발 방지 — systemd --user 타이머 신설** (`~/.config/systemd/user/docker-cache-prune.{service,timer}`):
- 매일 04:00 KST `docker builder prune -f --filter until=72h` → 3일 지난 미사용 캐시만 제거(최근 캐시 보존 → 빌드 속도 유지). 정상상태 ~3일치(~15GB)로 수렴.

**교훈**: docker 빌드 호스트는 캐시 자동정리가 없어 **주기적 prune이 표준 운영 위생**이다. PR머신이 빌드 빈도를 높여 정리 주기를 "가끔"에서 "매일"로 앞당겼다. 프로덕션 호스트라 디스크 알림·상한 관리를 상시 둘 것.

---

## 2. [후속] pip 캐시 볼륨이 SELinux로 사망 → verify 콜드빌드 유발

**상황**: 위 `-a` 대청소로 torch 빌드캐시까지 날아가, 다음 ai-server(Python) PR verify가 **풀 콜드빌드 ~12분(재다운로드 포함)** 1회 발생.

**원인**: verify는 pip 휠 캐시 볼륨(`-v {STATE_DIR}/pipcache:/root/.cache/pip`)으로 재다운로드를 피하도록 설계됐으나, arm1이 **SELinux Enforcing**이라 마운트가 **EACCES로 막혀 `state/pipcache`가 계속 0 byte**(캐시가 처음부터 미작동). 그래서 빌드캐시가 유일한 속도 수단이 됨.

**해결(후속, PR머신 코드 변경)**: 마운트에 SELinux `:z` 라벨 추가 → `-v {STATE_DIR}/pipcache:/root/.cache/pip:z`. `:z`는 소스에 컨테이너 접근 가능한 라벨(`container_file_t`)을 붙여 Enforcing에서도 read/write 허용. 고치면 pip 캐시가 살아나 빌드캐시를 프루닝해도 재다운로드 없음 → **디스크 정리↔빌드 속도 양립**. PR머신 verify 코드 1줄이라 워크트리+PR로 진행(Trivial~Normal).

**주의**: prune 타이머(1번)와 `:z`(2번)는 **짝**이다 — 타이머가 캐시를 지워도 `:z`가 없으면 콜드빌드가 반복될 수 있다. 둘 다 있어야 "디스크도 안 차고 검수도 빠른" 상태가 완성된다.
