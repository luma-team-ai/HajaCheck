package com.hajacheck.payment.service;

/**
 * 결제 원장의 현재 정산 상태(#988 리뷰 P2) — PG 가 "이미 처리된 결제"로 거절했을 때, 그게 <b>우리 원장
 * 기준으로도 성사된 결제인지</b>를 트랜잭션 밖에서 판단하기 위해 읽어오는 값.
 *
 * <p>같은 주문에 confirm 두 건이 동시에 도착하면 둘 다 PG 를 호출할 수 있고, 진 쪽은 "이미 처리된 결제"
 * 오류를 받는다. 그때 그대로 502 를 돌려주면 <b>결제는 성공했는데 사용자에게는 실패로 보여</b> 재결제를
 * 유도하게 된다. 이 상태를 다시 읽어 PAID 면 멱등 성공으로 응답한다.
 *
 * @param paid                  우리 원장 기준 승인 완료 여부
 * @param planApplicationPending PAID 인데 구독 전이가 아직 반영되지 않음(전이만 재시도하면 되는 상태)
 */
public record PaymentSettlementState(boolean paid, boolean planApplicationPending) {
}
