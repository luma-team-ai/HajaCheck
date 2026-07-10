"""AI-DL 학습 스크립트 예시 — tracking.py 사용법 (황승현 참고용)

실제 사용: 이 파일 옆에 tracking.py를 두고(Colab이면 둘 다 업로드),
아래 패턴대로 본인 학습 루프에 로깅 호출만 끼워넣으면 됩니다.
"""
from tracking import start_run, log_hyperparams, log_epoch_metrics, log_artifact

# 1) 실험 시작 — 데이터셋 버전과 메모를 남기면 나중에 실험 목록에서 구분하기 쉬움
with start_run(dataset_version="v1", notes="AI Hub 균열탐지 1차 학습"):

    # 2) 하이퍼파라미터 기록 — 학습 시작 전 한 번
    log_hyperparams(model="yolov8s-seg", epochs=50, lr=0.001, batch_size=16)

    # 3) 실제 학습 루프 (여기는 예시 — 진짜 YOLOv8 학습 코드로 교체)
    for epoch in range(50):
        # ... model.train() 등 실제 학습 ...
        current_mAP = 0.5 + epoch * 0.005  # 실제로는 검증 결과값 사용
        current_loss = 1.0 - epoch * 0.01

        # 매 epoch마다 지표 기록
        log_epoch_metrics(epoch, mAP=current_mAP, loss=current_loss)

    # 4) 학습 끝나면 체크포인트 파일 첨부 (경로는 실제 저장된 모델 파일로 교체)
    # log_artifact("runs/train/weights/best.pt")

print("학습 + 트래킹 완료. mlflow ui 로 대시보드에서 확인하세요")
