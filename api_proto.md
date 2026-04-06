## API 명세서

> Base URL: `https://api.flash-sale.com/api/v1`
> 

> Content-Type: `application/json`
> 

> Auth: `Authorization: Bearer {access_token}` (JWT)
> 

---

### 🔐 인증

| Role | 설명 |
| --- | --- |
| USER | 일반 사용자. 상품 조회, 구매, 내역 확인 가능 |
| ADMIN | 관리자. 상품/세일 관리, 모니터링, 정산 접근 가능 |

---

### 📦 상품 (UC-01 · UC-08 · UC-09)

**GET `/products`** — 전체 상품 목록 조회

전체 상품 목록을 조회합니다. Redis 캐싱 + PER 알고리즘으로 Cache Stampede를 방지합니다.

Query Parameters

| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| saleStatus | string | 필터: ALL · ACTIVE · SCHEDULED · ENDED |
| cursor | string | 커서 기반 페이지네이션 커서값 |
| size | int | 페이지 크기 (기본값: 20, 최대: 100) |

Response 200

```json
{
  "data": [
    {
      "id": 1,
      "name": "올리브영 세럼 50ml",
      "price": 29000,
      "saleEvent": {
        "status": "ACTIVE",
        "remainingStock": 43,
        "startsAt": "2025-01-01T10:00:00"
      }
    }
  ],
  "nextCursor": "eyJpZCI6MjB9",
  "hasNext": true
}
```

---

**GET `/products/{id}`** — 상품 상세 조회

특정 상품의 상세 정보를 조회합니다. 재고는 Redis에서 직접 읽어 실시간성을 보장합니다.

Response 200

```json
{
  "id": 1,
  "name": "올리브영 세럼 50ml",
  "description": "...",
  "price": 29000,
  "saleEvent": {
    "id": 10,
    "totalStock": 100,
    "reservedStock": 12,
    "soldStock": 45,
    "status": "ACTIVE",
    "startsAt": "2025-01-01T10:00:00",
    "endsAt": "2025-01-01T12:00:00"
  }
}
```

---

**POST `/admin/products`** — 상품 등록 (ADMIN)

새 상품을 등록합니다. 등록 즉시 Redis 캐시를 무효화(Cache Invalidation)합니다.

Request Body

```json
{
  "name": "올리브영 세럼 50ml",
  "description": "...",
  "price": 29000
}
```

| 상태코드 | 설명 |
| --- | --- |
| 201 | 상품 등록 성공 |
| 400 | 유효성 검사 실패 |
| 403 | ADMIN 권한 없음 |

---

**DELETE `/admin/products/{id}`** — 상품 삭제 (ADMIN)

상품을 논리 삭제(Soft Delete)합니다. 진행 중인 주문이 있는 경우 삭제가 거부됩니다.

> ⚠️ 상품 삭제 시 연관된 세일 이벤트도 함께 `HALTED` 상태로 중지됩니다. 관련 Redis 키는 일괄 정리됩니다.
> 

| 상태코드 | 에러코드 | 설명 |
| --- | --- | --- |
| 204 | — | 삭제 성공 |
| 409 | PRODUCT_HAS_ACTIVE_ORDERS | 진행 중인 주문 존재 |

---

### ⚡ 세일 이벤트 (UC-10 · UC-11 · UC-12)

**POST `/admin/sales`** — 세일 이벤트 등록 (ADMIN)

세일 이벤트를 생성하고 예약 오픈을 설정합니다. `startsAt` 시각에 Distributed Lock Scheduler가 단일 인스턴스에서만 활성화를 처리합니다.

Request Body

```json
{
  "productId": 1,
  "totalStock": 100,
  "startsAt": "2025-06-01T10:00:00",
  "endsAt": "2025-06-01T12:00:00"
}
```

| 상태코드 | 에러코드 | 설명 |
| --- | --- | --- |
| 201 | — | 세일 이벤트 생성 성공 |
| 409 | SALE_EVENT_ALREADY_EXISTS | 해당 상품에 이미 활성 세일 존재 |

---

**POST `/admin/sales/{id}/halt`** — 이벤트 긴급 중단 (ADMIN)

Redis에 Feature Flag를 즉시 설정해 모든 구매 요청 API를 차단합니다. 배포 없이 실시간 제어 가능합니다.

Request Body

```json
{
  "reason": "시스템 과부하로 인한 긴급 중단"
}
```

---

**GET `/admin/sales/{id}/stock`** — 실시간 재고 현황 (ADMIN)

Redis에서 직접 재고를 읽어 실시간 현황을 반환합니다. DB와의 정합성 차이도 함께 노출합니다. `isConsistent: false`가 반환되면 아래 동기화 API로 강제 보정할 수 있습니다.

Response 200

```json
{
  "totalStock": 100,
  "reservedStock": 12,
  "soldStock": 45,
  "availableStock": 43,
  "redisStock": 43,
  "isConsistent": true
}
```

---

**POST `/admin/sales/{id}/sync`** — 재고 강제 동기화 (ADMIN)

`isConsistent: false` 발생 시 Redis 재고를 DB 기준값으로 강제 업데이트합니다. 운영 중 정합성 불일치를 즉각 해소할 수 있습니다.

```json
// Response 200
{
  "syncedStock": 43,
  "previousRedisStock": 40,
  "syncedAt": "2025-01-01T11:00:00"
}
```

---

### 🛒 주문 (UC-02 · UC-04 · UC-05 · UC-07)

**POST `/orders`** — 구매 요청 (재고 선점)

선착순 재고 선점을 시도합니다. Redis Lua Script로 Get-Check-Set을 원자적으로 처리해 Race Condition을 방지합니다. Redis SETNX로 동일 유저의 중복 요청을 차단합니다. 성공 시 주문이 `PENDING` 상태로 생성되고 토스페이먼츠 결제 URL을 반환합니다. 결제는 **5분 이내**에 완료해야 하며, 만료 시 배치 또는 Redis Expire Event가 주문을 `EXPIRED`로 변경하고 재고를 자동 복구합니다.

> ⚠️ 긴급 중단(is_halted=true) 상태인 세일 이벤트에 요청하면 즉시 `SALE_EVENT_HALTED` 에러를 반환합니다.
> 

> ⚠️ 대기열이 활성화된 세일의 경우, `X-Queue-Token` 헤더에 유효한 토큰을 포함해야 합니다. 서버는 Redis에서 해당 토큰의 유효성을 검증한 후 주문을 처리합니다. 토큰이 없거나 유효하지 않으면 `INVALID_QUEUE_TOKEN` 에러를 반환합니다.
> 

Request Header (대기열 활성화 시)

```
X-Queue-Token: queue-token-abc
Idempotency-Key: {client-generated-uuid}
```

Request Body

```json
{
  "saleEventId": 10,
  "quantity": 1
}
```

Response 201

```json
{
  "orderId": "order-uuid-1234",
  "idempotencyKey": "idem-uuid-5678",
  "totalAmount": 29000,
  "status": "PENDING",
  "expiresAt": "2025-01-01T10:05:00",
  "payment": {
    "checkoutUrl": "https://pay.toss.im/..."
  }
}
```

| 상태코드 | 에러코드 | 설명 |
| --- | --- | --- |
| 400 | OUT_OF_STOCK | 재고 소진 |
| 409 | DUPLICATE_ORDER_REQUEST | 중복 요청 |
| 409 | SALE_EVENT_HALTED | 긴급 중단 상태 |
| 401 | INVALID_QUEUE_TOKEN | 대기열 토큰 없음 또는 유효하지 않음 |

---

**GET `/orders/{id}`** — 주문 상태 조회

주문 상태를 조회합니다. 실시간 수신이 필요하면 SSE 엔드포인트를 사용하세요.

Response 200

```json
{
  "orderId": "order-uuid-1234",
  "status": "PAID",
  "totalAmount": 29000,
  "product": {
    "name": "올리브영 세럼 50ml"
  },
  "createdAt": "2025-01-01T10:00:00"
}
```

---

**GET `/orders/{id}/stream`** — 주문 상태 실시간 수신 (SSE)

Server-Sent Events로 주문 상태 변경을 실시간으로 수신합니다. Polling 없이 결제 완료 여부를 즉시 전달받을 수 있습니다. 상태가 `PAID` 또는 `FAILED`로 바뀌면 서버가 연결을 종료합니다.

> 💡 **연결 순서 중요**: `POST /orders` 응답을 받은 직후 SSE 연결을 먼저 맺고, 그 다음 결제창(Toss SDK)을 오픈해야 합니다. Webhook이 SSE 연결 전에 도착하면 결제 완료 알림을 놓칠 수 있습니다.
> 

> 권장 클라이언트 흐름: `POST /orders` → `GET /orders/{id}/stream` 연결 → 결제창 오픈 → `POST /payments/confirm`
> 

```
Content-Type: text/event-stream

event: ORDER_STATUS_CHANGED
data: {
  "orderId": "order-uuid-1234",
  "status": "PAID",
  "changedAt": "2025-01-01T10:01:30"
}
```

---

**GET `/orders`** — 구매 내역 조회

내 구매 내역을 커서 기반 페이지네이션으로 조회합니다. Offset 방식 대비 대용량 데이터에서 성능이 일정하게 유지됩니다.

| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| cursor | string | 마지막으로 받은 커서값 (최초 요청 시 생략) |
| size | int | 페이지 크기 (기본값: 20) |
| status | string | 필터: PAID · CANCELLED · FAILED |

Response 200

```json
{
  "data": [
    {
      "orderId": "order-uuid-1234",
      "productName": "올리브영 세럼",
      "status": "PAID",
      "totalAmount": 29000,
      "createdAt": "2025-01-01T10:00:00"
    }
  ],
  "nextCursor": "eyJpZCI6MjB9",
  "hasNext": false
}
```

---

**POST `/orders/{id}/cancel`** — 주문 취소 및 환불

주문을 취소하고 환불을 요청합니다. Saga Pattern으로 토스페이먼츠 환불 → Redis 재고 복구 → DB 주문 상태 변경을 처리하며, 중간 실패 시 보상 트랜잭션이 실행됩니다.

Request Body

```json
{
  "cancelReason": "단순 변심"
}
```

| 상태코드 | 에러코드 | 설명 |
| --- | --- | --- |
| 200 | — | 취소 및 환불 완료 |
| 409 | ORDER_ALREADY_CANCELLED | 이미 취소된 주문 |
| 400 | ORDER_NOT_CANCELLABLE | 취소 불가 상태 |

---

### 💳 결제 (UC-03 · UC-13) — 토스페이먼츠 연동

> ℹ️ 토스페이먼츠 시크릿 키는 서버에서만 사용합니다. `Authorization: Basic Base64(secretKey:)` 형식으로 인코딩해 요청합니다.
> 

**POST `/payments/confirm`** — 결제 승인

프론트에서 토스페이먼츠 결제창 인증 완료 후 `paymentKey`를 전달받아 최종 승인을 요청합니다. 서버에서 **금액 위변조 검증**(`amount == ORDER.total_amount`)을 반드시 수행한 뒤 토스페이먼츠 승인 API를 호출합니다.

> ⚠️ 금액 불일치 시 토스페이먼츠 승인 API를 호출하지 않고 즉시 `PAYMENT_AMOUNT_MISMATCH`를 반환합니다.
> 

> 💡 `Idempotency-Key` 헤더를 반드시 포함하세요. 토스페이먼츠 API 호출 중 타임아웃 발생 시, 동일 키로 재시도하면 토스 측에서 중복 결제를 방지합니다.
> 

Request Header

```
Idempotency-Key: {client-generated-uuid}
```

Request Body

```json
{
  "paymentKey": "tgen_20240101_abc123",
  "orderId": "order-uuid-1234",
  "amount": 29000
}
```

내부 처리 흐름

```
1. amount == ORDER.total_amount 검증
2. POST /v1/payments/confirm (토스페이먼츠)
3. ORDER.status = PAID
4. Kafka: ORDER_PAID 이벤트 발행
5. SSE: 결제 완료 푸시
```

Response 200

```json
{
  "orderId": "order-uuid-1234",
  "status": "PAID",
  "paymentKey": "tgen_20240101_abc123",
  "approvedAt": "2025-01-01T10:01:30"
}
```

| 상태코드 | 에러코드 | 설명 |
| --- | --- | --- |
| 400 | PAYMENT_AMOUNT_MISMATCH | 금액 위변조 감지 |
| 400 | ORDER_PAYMENT_EXPIRED | 결제 만료 (5분 초과, 재고 이미 복구됨) |
| 400 | ORDER_EXPIRED | 선점 시간 만료로 주문이 이미 EXPIRED 상태 |
| 500 | TOSS_PAYMENTS_ERROR | 토스페이먼츠 오류 |

---

**WEBHOOK POST `/webhooks/toss`** — 토스페이먼츠 Webhook 수신

토스페이먼츠가 결제 상태 변경 시 발송하는 `PAYMENT_STATUS_CHANGED` 이벤트를 수신합니다. `tosspayments-webhook-transmission-id` 헤더를 Idempotency Key로 사용해 중복 수신을 방지합니다.

수신 Event Body

```json
{
  "eventType": "PAYMENT_STATUS_CHANGED",
  "createdAt": "2025-01-01T10:01:30.000000",
  "data": {
    "paymentKey": "tgen_20240101_abc123",
    "orderId": "order-uuid-1234",
    "status": "DONE",
    "totalAmount": 29000,
    "approvedAt": "2025-01-01T10:01:30+09:00"
  }
}
```

처리 로직

```
DONE    → ORDER.status = PAID (2차 보정)
ABORTED → ORDER.status = FAILED + Redis 재고 복구
EXPIRED → ORDER.status = FAILED + Redis 재고 복구
```

---

### ⏳ 대기열 (UC-19)

**POST `/queue/enter`** — 대기열 진입

트래픽 폭주 시 활성화되는 가상 대기열에 진입합니다. Redis Sorted Set에 `score = System.currentTimeMillis()`로 등록됩니다.

Request Body

```json
{
  "saleEventId": 10
}
```

Response 200

```json
{
  "rank": 1523,
  "estimatedWaitSeconds": 76,
  "queueToken": "queue-token-abc"
}
```

---

**GET `/queue/status`** — 대기 순번 조회

현재 대기 순번과 예상 대기 시간을 조회합니다. `rank = 0`이면 즉시 구매 요청이 가능합니다.

| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| saleEventId | long | 세일 이벤트 ID |
| queueToken | string | 진입 시 발급받은 토큰 |

Response 200

```json
{
  "rank": 0,
  "canPurchase": true,
  "estimatedWaitSeconds": 0
}
```

---

### 📊 정산 (UC-14)

**GET `/admin/settlements`** — 정산 목록 조회 (ADMIN)

세일 이벤트별 정산 정보를 조회합니다. 세일 종료 후 Spring Batch로 집계되고 결과는 Redis에 캐싱됩니다.

| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| from | string | 조회 시작일 (yyyy-MM-dd) |
| to | string | 조회 종료일 (yyyy-MM-dd) |
| status | string | CALCULATING · DONE |

Response 200

```json
{
  "data": [
    {
      "saleEventId": 10,
      "totalOrders": 57,
      "totalAmount": 1653000,
      "refundAmount": 29000,
      "netAmount": 1624000,
      "status": "DONE",
      "periodFrom": "2025-01-01T10:00:00",
      "periodTo": "2025-01-01T12:00:00"
    }
  ]
}
```

---

### 🔐 인증 (UC-25)

**POST `/auth/login`** — 로그인 및 JWT 발급

이메일/비밀번호로 로그인하고 Access Token과 Refresh Token을 발급합니다.

Request Body

```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

Response 200

```json
{
  "accessToken": "eyJhbGci...",
  "refreshToken": "eyJhbGci...",
  "expiresIn": 3600,
  "user": {
    "id": 1,
    "email": "user@example.com",
    "name": "홍길동",
    "role": "USER"
  }
}
```

| 상태코드 | 에러코드 | 설명 |
| --- | --- | --- |
| 401 | INVALID_CREDENTIALS | 이메일 또는 비밀번호 불일치 |

---

**GET `/auth/me`** — 현재 유저 정보 조회

로그인한 유저의 프로필을 반환합니다. 결제 시 이름/연락처 자동 입력에 활용합니다.

Response 200

```json
{
  "id": 1,
  "email": "user@example.com",
  "name": "홍길동",
  "phone": "010-1234-5678",
  "role": "USER"
}
```

---

**POST `/auth/refresh`** — Access Token 재발급

Access Token 만료 시 Refresh Token으로 새 Access Token을 발급합니다.

Request Body

```json
{
  "refreshToken": "eyJhbGci..."
}
```

Response 200

```json
{
  "accessToken": "eyJhbGci...",
  "expiresIn": 3600
}
```

| 상태코드 | 에러코드 | 설명 |
| --- | --- | --- |
| 401 | INVALID_REFRESH_TOKEN | Refresh Token 만료 또는 위조 → 재로그인 필요 |

---

### 🕐 시스템 (UC-23)

**GET `/system/time`** — 서버 시간 조회

서버의 현재 Unix 타임스탬프를 반환합니다. 클라이언트는 최초 접속 시 이 값으로 로컬 시계와의 오차(offset)를 계산하고, 이를 기준으로 정확한 세일 카운트다운을 구현합니다.

> 💡 **프론트엔드 활용**: `offset = serverTime - Date.now()` 를 저장해두고, 이후 모든 시간 계산에 더해서 사용합니다. 사용자 PC 시계가 느려도 정각 오픈 시 버튼이 정확히 활성화됩니다.
> 

Response 200

```json
{
  "serverTime": 1735689600000,
  "timezone": "Asia/Seoul"
}
```

---

### 📡 실시간 재고 알림 (UC-24)

**GET `/products/stock-stream`** — 전역 재고 푸시 (SSE)

특정 상품의 재고가 임계치 이하로 떨어지거나 품절되면 모든 접속자에게 SSE로 이벤트를 푸시합니다. 클라이언트는 이 이벤트를 받아 버튼을 즉시 `품절`로 변경하거나 잔여 수량 뱃지를 업데이트합니다.

Query Parameters

| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| saleEventId | long | 구독할 세일 이벤트 ID |

SSE Event

```
Content-Type: text/event-stream

event: STOCK_UPDATED
data: {
  "saleEventId": 10,
  "remainingStock": 5,
  "isSoldOut": false
}

event: STOCK_SOLD_OUT
data: {
  "saleEventId": 10,
  "remainingStock": 0,
  "isSoldOut": true
}
```

---

### 💳 결제 결과 랜딩 (UC-26)

**프론트엔드 경로**: `/order/success?paymentKey=...&orderId=...&amount=...`

토스페이먼츠 결제창 완료 후 브라우저가 이 URL로 리다이렉트됩니다. 페이지가 마운트될 때 쿼리 파라미터를 읽어 `POST /payments/confirm`을 자동 호출합니다.

```tsx
// 프론트엔드 처리 흐름
const { paymentKey, orderId, amount } = useSearchParams()

useEffect(() => {
  confirmPayment({ paymentKey, orderId, amount })
    .then(() => showSuccess('구매가 완료되었습니다!'))
    .catch((err) => {
      if (err.code === 'ORDER_EXPIRED')
        showError('재고 선점 시간이 초과되었습니다.')
      else
        showError('결제에 실패하였습니다.')
    })
}, [])
```

| 결과 | 메시지 예시 |
| --- | --- |
| 승인 성공 | 구매가 완료되었습니다! |
| ORDER_EXPIRED | 재고 선점 시간이 초과되었습니다. |
| PAYMENT_AMOUNT_MISMATCH | 결제 금액이 일치하지 않습니다. |
| TOSS_PAYMENTS_ERROR | 결제 처리 중 오류가 발생했습니다. |