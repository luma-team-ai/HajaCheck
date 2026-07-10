# MLflow 실험 추적 (AI-DL 학습용)

담당: 황승현(AI-DL 실사용) / 오영석(공통 인프라 제공, 챕터9 모니터링 겸)

## 설치

```bash
pip install -r ml/requirements.txt
```

Colab에서도 동일 — 노트북 첫 셀에 `!pip install mlflow==2.19.0` 후 이 파일(`ml/tracking.py`)만 레포에서 복사해 쓰면 됩니다.

## 사용법

```python
from ml.tracking import start_run, log_epoch_metrics, log_hyperparams

with start_run(dataset_version="v1", notes="1차 학습 — AI Hub 균열탐지 데이터셋"):
    log_hyperparams(model="yolov8s-seg", epochs=50, lr=0.001)
    for epoch in range(50):
        # ... 학습 루프 ...
        log_epoch_metrics(epoch, mAP=current_map, loss=current_loss)
```

## 대시보드 확인

```bash
mlflow ui
```
`http://localhost:5000`에서 실험별 mAP/loss 곡선 확인.

## 트래킹 서버

지금은 로컬 파일(`./mlruns`)에 기록 — 인프라 없이 바로 사용 가능. `AI 운영 서버 세팅`(07/24) 완료 후 원격 서버로 옮기면 `MLFLOW_TRACKING_URI` 환경변수만 서버 주소로 바꾸면 됩니다(코드 변경 없음).

## 실험 이름 규칙

`{날짜}_{데이터셋버전}` — PRD §6.2 모델 버전 관리 규칙과 동일. 예: `20260710_v1`
