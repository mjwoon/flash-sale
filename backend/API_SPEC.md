# Flash Sale API 명세서

> 최종 수정일: 2026-04-05
> API 버전: v1

---

## 목차

1. [개요](#1-개요)
2. [Base URL 및 공통 헤더](#2-base-url-및-공통-헤더)
3. [인증 및 인가](#3-인증-및-인가)
4. [공통 에러 형식](#4-공통-에러-형식)
5. [공통 에러 코드 참조표](#5-공통-에러-코드-참조표)
6. [인증 API](#6-인증-api)
   - [6.1 로그인 및 JWT 발급](#61-post-authlogin--로그인-및-jwt-발급)
   - [6.2 현재 유저 정보 조회](#62-get-authme--현재-유저-정보-조회)
   - [6.3 Access Token 재발급](#63-post-authrefresh--access-token-재발급)
7. [상품 API](#7-상품-api)
   - [7.1 상품 목록 조회](#71-get-products--상품-목록-조회)
   - [7.2 상품 상세 조회](#72-get-productsid--상품-상세-조회)
   - [7.3 상품 등록 (ADMIN)](#73-post-adminproducts--상품-등록)
   - [7.4 상품 삭제 (ADMIN)](#74-delete-adminproductsid--상품-삭제)
8. [세일 이벤트 API](#8-세일-이벤트-api)
   - [8.1 세일 이벤트 등록 (ADMIN)](#81-post-adminsales--세일-이벤트-등록)
   - [8.2 이벤트 긴급 중단 (ADMIN)](#82-post-adminsalesidhalt--이벤트-긴급-중단)
   - [8.3 실시간 재고 현황 (ADMIN)](#83-get-adminsalesidstock--실시간-재고-현황)
   - [8.4 재고 강제 동기화 (ADMIN)](#84-post-adminsalesidsync--재고-강제-동기화)
9. [주문 API](#9-주문-api)
   - [9.1 구매 요청 (재고 선점)](#91-post-orders--구매-요청)
   - [9.2 주문 상태 조회](#92-get-ordersid--주문-상태-조회)
   - [9.3 주문 상태 실시간 수신 (SSE)](#93-get-ordersidstream--주문-상태-실시간-수신-sse)
   - [9.4 구매 내역 조회](#94-get-orders--구매-내역-조회)
   - [9.5 주문 취소 및 환불](#95-post-ordersidcancel--주문-취소-및-환불)
10. [결제 API](#10-결제-api)
    - [10.1 결제 승인](#101-post-paymentsconfirm--결제-승인)
    - [10.2 토스페이먼츠 Webhook 수신](#102-post-webhookstoss--토스페이먼츠-webhook-수신)
11. [대기열 API](#11-대기열-api)
    - [11.1 대기열 진입](#111-post-queueenter--대기열-진입)
    - [11.2 대기 순번 조회](#112-get-queuestatus--대기-순번-조회)
12. [정산 API](#12-정산-api)
    - [12.1 정산 목록 조회 (ADMIN)](#121-get-adminsettlements--정산-목록-조회)
13. [시스템 API](#13-시스템-api)
    - [13.1 서버 시간 조회](#131-get-systemtime--서버-시간-조회)
14. [실시간 재고 알림 (SSE)](#14-실시간-재고-알림-sse)
    - [14.1 전역 재고 푸시](#141-get-productsstock-stream--전역-재고-푸시-sse)
15. [프론트엔드 처리 흐름](#15-프론트엔드-처리-흐름)

---

## 1. 개요

Flash Sale API는 한정 수량 선착순 판매 서비스의 핵심 기능을 제공합니다. 고트래픽 환경에서의 재고 정합성과 결제 안정성을 보장하기 위해 아래 기술 전략을 적용합니다.

| 관심사 | 적용 기술 |
|---|---|
| 재고 선점 원자성 | Redis Lua Script (Get-Check-Decrement 원자 실행) |
| 중복 요청 차단 | Redis SETNX 기반 Idempotency Key |
| Cache Stampede 방지 | PER (Probabilistic Early Recomputation) 알고리즘 |
| 세일 이벤트 단일 활성화 | Distributed Lock Scheduler (단일 인스턴스 보장) |
| 실시간 제어 | Redis Feature Flag (배포 없이 즉시 구매 차단) |
| 결제 취소 정합성 | Saga Pattern (보상 트랜잭션) |
| 이벤트 기반 상태 전파 | Kafka (ORDER_PAID 이벤트 발행) |
| 실시간 클라이언트 알림 | Server-Sent Events (SSE) |
| 대기열 순서 관리 | Redis Sorted Set (score = 진입 시각 ms) |
| 정산 집계 | Spring Batch + Redis 캐싱 |

---

## 2. Base URL 및 공통 헤더

### Base URL

```
https://api.flash-sale.com/api/v1
```

### 공통 요청 헤더

| 헤더 | 필수 | 설명 |
|---|---|---|
| `Content-Type` | 필수 | `application/json` |
| `Authorization` | 인증 필요 엔드포인트 | `Bearer {access_token}` |
| `Idempotency-Key` | 일부 엔드포인트 필수 | 멱등성 보장을 위한 UUID. POST /orders, POST /payments/confirm에서 필수 |
| `X-Queue-Token` | 대기열 활성화 시 필수 | POST /orders 요청 시 대기열 통과 토큰 |

---

## 3. 인증 및 인가

### 인증 방식

JWT (JSON Web Token) Bearer 인증을 사용합니다.

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

- **Access Token**: 만료 시간 3600초 (1시간). 만료 시 `POST /auth/refresh`로 재발급
- **Refresh Token**: 장기 유효. 탈취 방지를 위해 서버 측 관리

### 역할 (Role)

| 역할 | 설명 | 접근 가능 기능 |
|---|---|---|
| `USER` | 일반 사용자 | 상품 조회, 구매 요청, 결제, 주문 내역 조회, 주문 취소 |
| `ADMIN` | 관리자 | 상품·세일 이벤트 관리, 실시간 재고 모니터링, 긴급 중단, 정산 조회 |

### 인증 오류 응답

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `401` | `UNAUTHORIZED` | Authorization 헤더 누락 또는 토큰 형식 오류 |
| `401` | `TOKEN_EXPIRED` | Access Token 만료 |
| `403` | `FORBIDDEN` | 권한 없음 (예: USER가 ADMIN 전용 엔드포인트 호출) |

---

## 4. 공통 에러 형식

모든 에러 응답은 아래 JSON 구조를 따릅니다.

```json
{
  "code": "ERROR_CODE",
  "message": "사람이 읽을 수 있는 에러 설명",
  "timestamp": "2025-01-01T10:00:00"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `code` | string | 에러를 식별하는 고유 코드. 클라이언트 분기 처리에 사용 |
| `message` | string | 에러 원인에 대한 설명 (디버깅용, 사용자에게 그대로 노출 비권장) |
| `timestamp` | string (ISO 8601) | 에러 발생 시각 |

---

## 5. 공통 에러 코드 참조표

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `400` | `INVALID_INPUT` | 요청 파라미터 또는 Body 유효성 검사 실패 |
| `400` | `PAYMENT_AMOUNT_MISMATCH` | 결제 금액이 주문 금액과 불일치 |
| `400` | `ORDER_PAYMENT_EXPIRED` | 결제 승인 요청 시 주문 선점 시간(5분) 초과 |
| `400` | `ORDER_EXPIRED` | 주문이 만료된 상태 |
| `400` | `ORDER_NOT_CANCELLABLE` | 취소 불가 상태의 주문 (예: 이미 배송 중) |
| `400` | `OUT_OF_STOCK` | 재고 부족으로 구매 요청 실패 |
| `401` | `UNAUTHORIZED` | 인증 토큰 누락 또는 형식 오류 |
| `401` | `TOKEN_EXPIRED` | Access Token 만료 |
| `401` | `INVALID_CREDENTIALS` | 이메일 또는 비밀번호 불일치 |
| `401` | `INVALID_REFRESH_TOKEN` | Refresh Token 만료 또는 위조 |
| `401` | `INVALID_QUEUE_TOKEN` | 유효하지 않거나 만료된 대기열 토큰 |
| `403` | `FORBIDDEN` | 해당 리소스에 대한 접근 권한 없음 |
| `404` | `PRODUCT_NOT_FOUND` | 존재하지 않는 상품 |
| `404` | `ORDER_NOT_FOUND` | 존재하지 않는 주문 |
| `404` | `SALE_EVENT_NOT_FOUND` | 존재하지 않는 세일 이벤트 |
| `409` | `DUPLICATE_ORDER_REQUEST` | 동일 Idempotency-Key로 중복 요청 감지 |
| `409` | `SALE_EVENT_HALTED` | 이벤트 긴급 중단 상태로 구매 불가 |
| `409` | `SALE_EVENT_ALREADY_EXISTS` | 해당 상품에 이미 활성 세일 이벤트 존재 |
| `409` | `ORDER_ALREADY_CANCELLED` | 이미 취소된 주문 |
| `409` | `PRODUCT_HAS_ACTIVE_ORDERS` | 진행 중인 주문이 있어 상품 삭제 불가 |
| `500` | `TOSS_PAYMENTS_ERROR` | 토스페이먼츠 API 호출 중 오류 발생 |
| `500` | `INTERNAL_SERVER_ERROR` | 서버 내부 오류 |

---

## 6. 인증 API

### 6.1 `POST /auth/login` — 로그인 및 JWT 발급

이메일과 비밀번호로 로그인하여 Access Token과 Refresh Token을 발급합니다.

- **인증**: 불필요
- **관련 유스케이스**: UC-25

#### Request Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `email` | string | 필수 | 사용자 이메일 |
| `password` | string | 필수 | 사용자 비밀번호 |

```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

#### Response `200 OK`

| 필드 | 타입 | 설명 |
|---|---|---|
| `accessToken` | string | JWT Access Token |
| `refreshToken` | string | JWT Refresh Token |
| `expiresIn` | integer | Access Token 만료 시간 (초 단위) |
| `user.id` | integer | 사용자 고유 ID |
| `user.email` | string | 사용자 이메일 |
| `user.name` | string | 사용자 이름 |
| `user.role` | string | 사용자 역할 (`USER` 또는 `ADMIN`) |

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

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `401` | `INVALID_CREDENTIALS` | 이메일 또는 비밀번호 불일치 |

---

### 6.2 `GET /auth/me` — 현재 유저 정보 조회

로그인한 사용자의 프로필 정보를 반환합니다. 결제 시 이름·연락처 자동 입력에 활용됩니다.

- **인증**: 필수 (USER)
- **관련 유스케이스**: UC-25

#### Response `200 OK`

| 필드 | 타입 | 설명 |
|---|---|---|
| `id` | integer | 사용자 고유 ID |
| `email` | string | 사용자 이메일 |
| `name` | string | 사용자 이름 |
| `phone` | string | 사용자 전화번호 |
| `role` | string | 사용자 역할 (`USER` 또는 `ADMIN`) |

```json
{
  "id": 1,
  "email": "user@example.com",
  "name": "홍길동",
  "phone": "010-1234-5678",
  "role": "USER"
}
```

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `401` | `UNAUTHORIZED` | 인증 토큰 누락 또는 만료 |

---

### 6.3 `POST /auth/refresh` — Access Token 재발급

Access Token 만료 시 Refresh Token을 사용하여 새 Access Token을 발급합니다.

- **인증**: 불필요 (Refresh Token으로 인증)
- **관련 유스케이스**: UC-25

#### Request Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `refreshToken` | string | 필수 | 발급받은 Refresh Token |

```json
{
  "refreshToken": "eyJhbGci..."
}
```

#### Response `200 OK`

| 필드 | 타입 | 설명 |
|---|---|---|
| `accessToken` | string | 새로 발급된 JWT Access Token |
| `expiresIn` | integer | Access Token 만료 시간 (초 단위) |

```json
{
  "accessToken": "eyJhbGci...",
  "expiresIn": 3600
}
```

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `401` | `INVALID_REFRESH_TOKEN` | Refresh Token 만료 또는 위조. 재로그인 필요 |

---

## 7. 상품 API

### 7.1 `GET /products` — 상품 목록 조회

전체 상품 목록을 커서 기반 페이지네이션으로 조회합니다.

- **인증**: 불필요
- **관련 유스케이스**: UC-01, UC-08, UC-09
- **성능**: Redis 캐싱 적용. PER (Probabilistic Early Recomputation) 알고리즘으로 Cache Stampede 방지

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|
| `saleStatus` | string | 선택 | `ALL` | 세일 상태 필터. `ALL` · `ACTIVE` · `SCHEDULED` · `ENDED` |
| `cursor` | string | 선택 | - | 이전 응답의 `nextCursor` 값. 첫 요청 시 생략 |
| `size` | integer | 선택 | `20` | 페이지당 항목 수. 최대 `100` |

#### Response `200 OK`

| 필드 | 타입 | 설명 |
|---|---|---|
| `data` | array | 상품 목록 |
| `data[].id` | integer | 상품 고유 ID |
| `data[].name` | string | 상품명 |
| `data[].price` | integer | 상품 정가 (원 단위) |
| `data[].saleEvent.status` | string | 세일 상태. `ACTIVE` · `SCHEDULED` · `ENDED` · `HALTED` |
| `data[].saleEvent.remainingStock` | integer | 현재 잔여 재고 수량 |
| `data[].saleEvent.startsAt` | string (ISO 8601) | 세일 시작 시각 |
| `nextCursor` | string | 다음 페이지 조회용 커서 토큰. `hasNext`가 `false`이면 `null` |
| `hasNext` | boolean | 다음 페이지 존재 여부 |

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

### 7.2 `GET /products/{id}` — 상품 상세 조회

특정 상품의 상세 정보와 세일 이벤트 현황을 조회합니다.

- **인증**: 불필요
- **관련 유스케이스**: UC-01
- **성능**: 재고 수량은 Redis에서 직접 읽어 실시간성 보장

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `id` | integer | 상품 고유 ID |

#### Response `200 OK`

| 필드 | 타입 | 설명 |
|---|---|---|
| `id` | integer | 상품 고유 ID |
| `name` | string | 상품명 |
| `description` | string | 상품 상세 설명 |
| `price` | integer | 상품 정가 (원 단위) |
| `saleEvent.id` | integer | 세일 이벤트 고유 ID |
| `saleEvent.totalStock` | integer | 세일 총 재고 수량 |
| `saleEvent.reservedStock` | integer | 선점(결제 대기) 중인 재고 수량 |
| `saleEvent.soldStock` | integer | 결제 완료된 재고 수량 |
| `saleEvent.status` | string | 세일 상태. `ACTIVE` · `SCHEDULED` · `ENDED` · `HALTED` |
| `saleEvent.startsAt` | string (ISO 8601) | 세일 시작 시각 |
| `saleEvent.endsAt` | string (ISO 8601) | 세일 종료 시각 |

```json
{
  "id": 1,
  "name": "올리브영 세럼 50ml",
  "description": "피부 장벽을 강화하는 고농축 세럼",
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

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `404` | `PRODUCT_NOT_FOUND` | 존재하지 않는 상품 ID |

---

### 7.3 `POST /admin/products` — 상품 등록

새 상품을 등록합니다. 등록 즉시 Redis 상품 목록 캐시를 무효화합니다.

- **인증**: 필수 (ADMIN)
- **관련 유스케이스**: UC-08
- **부수 효과**: 상품 등록 시 Redis 캐시 즉시 무효화 (Cache Invalidation)

#### Request Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `name` | string | 필수 | 상품명 |
| `description` | string | 선택 | 상품 상세 설명 |
| `price` | integer | 필수 | 상품 정가 (원 단위, 1 이상) |

```json
{
  "name": "올리브영 세럼 50ml",
  "description": "피부 장벽을 강화하는 고농축 세럼",
  "price": 29000
}
```

#### Response `201 Created`

등록된 상품 객체를 반환합니다.

```json
{
  "id": 1,
  "name": "올리브영 세럼 50ml",
  "description": "피부 장벽을 강화하는 고농축 세럼",
  "price": 29000
}
```

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `400` | `INVALID_INPUT` | 필수 필드 누락 또는 유효성 검사 실패 |
| `403` | `FORBIDDEN` | ADMIN 권한 없음 |

---

### 7.4 `DELETE /admin/products/{id}` — 상품 삭제

상품을 Soft Delete 처리합니다.

- **인증**: 필수 (ADMIN)
- **관련 유스케이스**: UC-09
- **주의**: 진행 중인 주문이 있는 경우 삭제가 거부됩니다.
- **부수 효과**: 삭제 시 연관 세일 이벤트를 `HALTED` 상태로 변경하고 Redis 키를 일괄 정리합니다.

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `id` | integer | 삭제할 상품 고유 ID |

#### Response `204 No Content`

본문 없이 성공 응답을 반환합니다.

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `403` | `FORBIDDEN` | ADMIN 권한 없음 |
| `404` | `PRODUCT_NOT_FOUND` | 존재하지 않는 상품 ID |
| `409` | `PRODUCT_HAS_ACTIVE_ORDERS` | 진행 중인 주문이 존재하여 삭제 불가 |

---

## 8. 세일 이벤트 API

### 8.1 `POST /admin/sales` — 세일 이벤트 등록

새 세일 이벤트를 등록합니다.

- **인증**: 필수 (ADMIN)
- **관련 유스케이스**: UC-10
- **동작**: Distributed Lock Scheduler가 `startsAt` 시각에 단일 인스턴스에서만 이벤트 활성화 처리를 수행합니다. 다중 서버 환경에서도 이벤트가 중복 활성화되지 않습니다.

#### Request Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `productId` | integer | 필수 | 세일 대상 상품 ID |
| `totalStock` | integer | 필수 | 세일 총 재고 수량 (1 이상) |
| `startsAt` | string (ISO 8601) | 필수 | 세일 시작 시각 |
| `endsAt` | string (ISO 8601) | 필수 | 세일 종료 시각 (`startsAt` 이후 시각) |

```json
{
  "productId": 1,
  "totalStock": 100,
  "startsAt": "2025-06-01T10:00:00",
  "endsAt": "2025-06-01T12:00:00"
}
```

#### Response `201 Created`

등록된 세일 이벤트 객체를 반환합니다.

```json
{
  "id": 10,
  "productId": 1,
  "totalStock": 100,
  "status": "SCHEDULED",
  "startsAt": "2025-06-01T10:00:00",
  "endsAt": "2025-06-01T12:00:00"
}
```

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `400` | `INVALID_INPUT` | 필수 필드 누락 또는 유효성 검사 실패 |
| `403` | `FORBIDDEN` | ADMIN 권한 없음 |
| `404` | `PRODUCT_NOT_FOUND` | 존재하지 않는 상품 ID |
| `409` | `SALE_EVENT_ALREADY_EXISTS` | 해당 상품에 이미 활성(`ACTIVE` 또는 `SCHEDULED`) 세일 이벤트 존재 |

---

### 8.2 `POST /admin/sales/{id}/halt` — 이벤트 긴급 중단

진행 중인 세일 이벤트를 즉시 중단합니다.

- **인증**: 필수 (ADMIN)
- **관련 유스케이스**: UC-11
- **동작**: Redis Feature Flag를 즉시 설정하여 모든 신규 구매 요청을 차단합니다. 서버 재배포 없이 실시간으로 제어 가능합니다.

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `id` | integer | 중단할 세일 이벤트 고유 ID |

#### Request Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `reason` | string | 필수 | 긴급 중단 사유 |

```json
{
  "reason": "시스템 과부하로 인한 긴급 중단"
}
```

#### Response `200 OK`

```json
{
  "id": 10,
  "status": "HALTED",
  "haltedAt": "2025-01-01T10:30:00",
  "reason": "시스템 과부하로 인한 긴급 중단"
}
```

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `403` | `FORBIDDEN` | ADMIN 권한 없음 |
| `404` | `SALE_EVENT_NOT_FOUND` | 존재하지 않는 세일 이벤트 ID |

---

### 8.3 `GET /admin/sales/{id}/stock` — 실시간 재고 현황

세일 이벤트의 실시간 재고 현황을 조회합니다.

- **인증**: 필수 (ADMIN)
- **관련 유스케이스**: UC-12
- **동작**: Redis에서 직접 읽어 실시간 현황을 반환합니다. DB와의 정합성 차이(`isConsistent: false`)가 감지되면 `POST /admin/sales/{id}/sync` API로 보정할 수 있습니다.

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `id` | integer | 세일 이벤트 고유 ID |

#### Response `200 OK`

| 필드 | 타입 | 설명 |
|---|---|---|
| `totalStock` | integer | 세일 총 재고 수량 |
| `reservedStock` | integer | 선점(결제 대기) 중인 재고 수량 |
| `soldStock` | integer | 결제 완료된 재고 수량 |
| `availableStock` | integer | 현재 구매 가능한 재고 수량 (`totalStock - reservedStock - soldStock`) |
| `redisStock` | integer | Redis에 저장된 잔여 재고 값 |
| `isConsistent` | boolean | Redis 재고와 DB 재고의 정합성 여부. `false`이면 동기화 필요 |

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

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `403` | `FORBIDDEN` | ADMIN 권한 없음 |
| `404` | `SALE_EVENT_NOT_FOUND` | 존재하지 않는 세일 이벤트 ID |

---

### 8.4 `POST /admin/sales/{id}/sync` — 재고 강제 동기화

Redis 재고를 DB 기준값으로 강제 동기화합니다.

- **인증**: 필수 (ADMIN)
- **관련 유스케이스**: UC-12
- **사용 시점**: `GET /admin/sales/{id}/stock` 응답에서 `isConsistent: false`가 반환될 때 사용합니다.

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `id` | integer | 세일 이벤트 고유 ID |

#### Response `200 OK`

| 필드 | 타입 | 설명 |
|---|---|---|
| `syncedStock` | integer | 동기화 후 Redis에 설정된 재고 값 (DB 기준값) |
| `previousRedisStock` | integer | 동기화 전 Redis에 저장되어 있던 재고 값 |
| `syncedAt` | string (ISO 8601) | 동기화 수행 시각 |

```json
{
  "syncedStock": 43,
  "previousRedisStock": 40,
  "syncedAt": "2025-01-01T11:00:00"
}
```

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `403` | `FORBIDDEN` | ADMIN 권한 없음 |
| `404` | `SALE_EVENT_NOT_FOUND` | 존재하지 않는 세일 이벤트 ID |

---

## 9. 주문 API

### 9.1 `POST /orders` — 구매 요청

재고를 선점하고 결제 URL을 발급합니다.

- **인증**: 필수 (USER)
- **관련 유스케이스**: UC-02
- **원자성**: Redis Lua Script로 Get-Check-Decrement를 원자적으로 실행하여 동시 요청 간 재고 초과 선점을 방지합니다.
- **중복 차단**: Redis SETNX로 동일 Idempotency-Key의 중복 요청을 차단합니다.
- **선점 유효 시간**: 성공 시 `PENDING` 상태로 5분간 유효. 만료 시 자동으로 `EXPIRED` 상태로 전환되고 재고가 복구됩니다.
- **주의**: `is_halted = true`인 이벤트에 요청 시 즉시 `SALE_EVENT_HALTED` 에러를 반환합니다.

#### Request Headers

| 헤더 | 필수 | 설명 |
|---|---|---|
| `Authorization` | 필수 | `Bearer {access_token}` |
| `Idempotency-Key` | 필수 | 중복 요청 방지용 UUID |
| `X-Queue-Token` | 대기열 활성화 시 필수 | `POST /queue/enter` 응답의 `queueToken` 값 |

#### Request Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `saleEventId` | integer | 필수 | 구매할 세일 이벤트 ID |
| `quantity` | integer | 필수 | 구매 수량 (현재 1만 허용) |

```json
{
  "saleEventId": 10,
  "quantity": 1
}
```

#### Response `201 Created`

| 필드 | 타입 | 설명 |
|---|---|---|
| `orderId` | string (UUID) | 생성된 주문 고유 ID |
| `idempotencyKey` | string (UUID) | 요청에 사용된 Idempotency-Key |
| `totalAmount` | integer | 결제 예정 금액 (원 단위) |
| `status` | string | 주문 상태. 초기값 `PENDING` |
| `expiresAt` | string (ISO 8601) | 재고 선점 만료 시각 (요청 시각으로부터 5분 후) |
| `payment.checkoutUrl` | string | 토스페이먼츠 결제창 URL |

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

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `400` | `OUT_OF_STOCK` | 잔여 재고 부족 |
| `400` | `INVALID_INPUT` | 요청 파라미터 유효성 검사 실패 |
| `401` | `UNAUTHORIZED` | 인증 토큰 누락 또는 만료 |
| `401` | `INVALID_QUEUE_TOKEN` | 유효하지 않거나 만료된 대기열 토큰 |
| `409` | `DUPLICATE_ORDER_REQUEST` | 동일 Idempotency-Key로 중복 요청 |
| `409` | `SALE_EVENT_HALTED` | 긴급 중단된 세일 이벤트 |

---

### 9.2 `GET /orders/{id}` — 주문 상태 조회

주문 상태를 단건 조회합니다. 실시간 상태 수신이 필요한 경우 `GET /orders/{id}/stream` (SSE)을 사용합니다.

- **인증**: 필수 (USER, 본인 주문만 조회 가능)
- **관련 유스케이스**: UC-04

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `id` | string (UUID) | 주문 고유 ID |

#### Response `200 OK`

| 필드 | 타입 | 설명 |
|---|---|---|
| `orderId` | string (UUID) | 주문 고유 ID |
| `status` | string | 주문 상태. `PENDING` · `PAID` · `CANCELLED` · `FAILED` · `EXPIRED` |
| `totalAmount` | integer | 결제 금액 (원 단위) |
| `product.name` | string | 주문 상품명 |
| `createdAt` | string (ISO 8601) | 주문 생성 시각 |

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

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `401` | `UNAUTHORIZED` | 인증 토큰 누락 또는 만료 |
| `403` | `FORBIDDEN` | 본인 주문이 아닌 경우 |
| `404` | `ORDER_NOT_FOUND` | 존재하지 않는 주문 ID |

---

### 9.3 `GET /orders/{id}/stream` — 주문 상태 실시간 수신 (SSE)

주문 상태 변경을 Server-Sent Events (SSE)로 실시간 수신합니다.

- **인증**: 필수 (USER, 본인 주문만)
- **관련 유스케이스**: UC-04, UC-05
- **Content-Type**: `text/event-stream`
- **연결 종료**: 주문 상태가 `PAID` 또는 `FAILED`로 전환되면 서버가 연결을 자동으로 종료합니다.

#### 권장 연동 흐름

```
POST /orders          → 주문 생성 및 재고 선점
GET /orders/{id}/stream  → SSE 연결 (백그라운드에서 상태 감시 시작)
결제창 오픈            → payment.checkoutUrl로 토스페이먼츠 결제창 열기
POST /payments/confirm → 결제 완료 후 승인 요청
SSE 이벤트 수신        → ORDER_STATUS_CHANGED (status: "PAID") → 완료 페이지 이동
```

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `id` | string (UUID) | 주문 고유 ID |

#### SSE 이벤트

**이벤트명**: `ORDER_STATUS_CHANGED`

| 필드 | 타입 | 설명 |
|---|---|---|
| `orderId` | string (UUID) | 주문 고유 ID |
| `status` | string | 변경된 주문 상태. `PAID` · `FAILED` · `EXPIRED` |
| `changedAt` | string (ISO 8601) | 상태 변경 시각 |

```
event: ORDER_STATUS_CHANGED
data: {"orderId": "order-uuid-1234", "status": "PAID", "changedAt": "2025-01-01T10:01:30"}
```

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `401` | `UNAUTHORIZED` | 인증 토큰 누락 또는 만료 |
| `403` | `FORBIDDEN` | 본인 주문이 아닌 경우 |
| `404` | `ORDER_NOT_FOUND` | 존재하지 않는 주문 ID |

---

### 9.4 `GET /orders` — 구매 내역 조회

로그인한 사용자의 구매 내역을 커서 기반 페이지네이션으로 조회합니다.

- **인증**: 필수 (USER)
- **관련 유스케이스**: UC-07

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|
| `cursor` | string | 선택 | - | 이전 응답의 `nextCursor` 값. 첫 요청 시 생략 |
| `size` | integer | 선택 | `20` | 페이지당 항목 수 |
| `status` | string | 선택 | - | 주문 상태 필터. `PAID` · `CANCELLED` · `FAILED` |

#### Response `200 OK`

| 필드 | 타입 | 설명 |
|---|---|---|
| `data` | array | 주문 목록 |
| `data[].orderId` | string (UUID) | 주문 고유 ID |
| `data[].productName` | string | 주문 상품명 |
| `data[].status` | string | 주문 상태 |
| `data[].totalAmount` | integer | 결제 금액 (원 단위) |
| `data[].createdAt` | string (ISO 8601) | 주문 생성 시각 |
| `nextCursor` | string | 다음 페이지 조회용 커서 토큰 |
| `hasNext` | boolean | 다음 페이지 존재 여부 |

```json
{
  "data": [
    {
      "orderId": "order-uuid-1234",
      "productName": "올리브영 세럼 50ml",
      "status": "PAID",
      "totalAmount": 29000,
      "createdAt": "2025-01-01T10:00:00"
    }
  ],
  "nextCursor": "eyJpZCI6MjB9",
  "hasNext": false
}
```

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `401` | `UNAUTHORIZED` | 인증 토큰 누락 또는 만료 |

---

### 9.5 `POST /orders/{id}/cancel` — 주문 취소 및 환불

주문을 취소하고 결제 금액을 환불합니다.

- **인증**: 필수 (USER, 본인 주문만)
- **관련 유스케이스**: UC-05

#### 취소 Saga 패턴 처리 흐름

주문 취소는 분산 트랜잭션 일관성을 보장하기 위해 Saga 패턴으로 처리됩니다.

```
1. 토스페이먼츠 환불 API 호출
   └─ 실패 시: 취소 요청 중단 (DB/Redis 변경 없음)
2. Redis 재고 복구 (+quantity)
   └─ 실패 시: 보상 트랜잭션 → 토스페이먼츠 재결제 취소 불가 상태 처리 + 알림
3. DB 주문 상태 → CANCELLED 변경
   └─ 실패 시: 보상 트랜잭션 → Redis 재고 재차감
```

각 단계에서 실패가 발생하면 이전 단계에 대한 보상 트랜잭션(Compensating Transaction)이 실행되어 데이터 정합성을 유지합니다.

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `id` | string (UUID) | 취소할 주문 고유 ID |

#### Request Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `cancelReason` | string | 필수 | 취소 사유 |

```json
{
  "cancelReason": "단순 변심"
}
```

#### Response `200 OK`

```json
{
  "orderId": "order-uuid-1234",
  "status": "CANCELLED",
  "cancelledAt": "2025-01-01T11:00:00",
  "refundAmount": 29000
}
```

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `400` | `ORDER_NOT_CANCELLABLE` | 취소 불가 상태의 주문 (예: 이미 배송 중) |
| `401` | `UNAUTHORIZED` | 인증 토큰 누락 또는 만료 |
| `403` | `FORBIDDEN` | 본인 주문이 아닌 경우 |
| `404` | `ORDER_NOT_FOUND` | 존재하지 않는 주문 ID |
| `409` | `ORDER_ALREADY_CANCELLED` | 이미 취소된 주문 |

---

## 10. 결제 API

> 토스페이먼츠 시크릿 키는 서버에서만 사용합니다. 클라이언트에 노출되지 않습니다.
> 서버-토스페이먼츠 간 통신: `Authorization: Basic Base64(secretKey:)` 형식

### 10.1 `POST /payments/confirm` — 결제 승인

토스페이먼츠 결제를 최종 승인합니다.

- **인증**: 필수 (USER)
- **관련 유스케이스**: UC-03
- **중요**: `Idempotency-Key` 헤더가 필수입니다. 타임아웃 후 재시도 시 중복 결제를 방지합니다.
- **금액 위변조 방지**: 요청 `amount`가 DB의 `ORDER.total_amount`와 일치하지 않으면 토스페이먼츠 API를 호출하지 않고 즉시 오류를 반환합니다.

#### Request Headers

| 헤더 | 필수 | 설명 |
|---|---|---|
| `Authorization` | 필수 | `Bearer {access_token}` |
| `Idempotency-Key` | 필수 | 중복 승인 방지용 UUID |

#### Request Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `paymentKey` | string | 필수 | 토스페이먼츠가 발급한 결제 키 |
| `orderId` | string (UUID) | 필수 | 승인할 주문 고유 ID |
| `amount` | integer | 필수 | 결제 금액 (원 단위). DB의 주문 금액과 반드시 일치해야 함 |

```json
{
  "paymentKey": "tgen_20240101_abc123",
  "orderId": "order-uuid-1234",
  "amount": 29000
}
```

#### 결제 승인 내부 처리 흐름

```
1. 금액 검증
   요청 amount == DB ORDER.total_amount 확인
   └─ 불일치 시: PAYMENT_AMOUNT_MISMATCH 반환 (토스 API 미호출)

2. 토스페이먼츠 승인 API 호출
   POST https://api.tosspayments.com/v1/payments/confirm
   └─ 오류 시: TOSS_PAYMENTS_ERROR 반환

3. DB 주문 상태 업데이트
   ORDER.status = PAID

4. Kafka 이벤트 발행
   토픽: ORDER_PAID
   └─ 정산 서비스, 알림 서비스 등 후속 처리

5. SSE 결제 완료 푸시
   GET /orders/{id}/stream 구독자에게 ORDER_STATUS_CHANGED (PAID) 전송
```

#### Response `200 OK`

| 필드 | 타입 | 설명 |
|---|---|---|
| `orderId` | string (UUID) | 승인된 주문 고유 ID |
| `status` | string | 주문 상태. `PAID` |
| `paymentKey` | string | 토스페이먼츠 결제 키 |
| `approvedAt` | string (ISO 8601) | 토스페이먼츠 승인 완료 시각 |

```json
{
  "orderId": "order-uuid-1234",
  "status": "PAID",
  "paymentKey": "tgen_20240101_abc123",
  "approvedAt": "2025-01-01T10:01:30"
}
```

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `400` | `PAYMENT_AMOUNT_MISMATCH` | 결제 금액이 주문 금액과 불일치 (토스 API 미호출) |
| `400` | `ORDER_PAYMENT_EXPIRED` | 재고 선점 유효 시간(5분) 초과 |
| `400` | `ORDER_EXPIRED` | 주문이 만료된 상태 |
| `401` | `UNAUTHORIZED` | 인증 토큰 누락 또는 만료 |
| `500` | `TOSS_PAYMENTS_ERROR` | 토스페이먼츠 API 호출 중 오류 |

---

### 10.2 `POST /webhooks/toss` — 토스페이먼츠 Webhook 수신

토스페이먼츠가 발송하는 결제 상태 변경 Webhook을 수신합니다.

- **인증**: 토스페이먼츠 서버 IP 화이트리스트 및 서명 검증
- **관련 유스케이스**: UC-13
- **멱등성**: `tosspayments-webhook-transmission-id` 헤더 값을 Idempotency Key로 사용하여 동일 이벤트 중복 처리를 방지합니다.

#### Webhook 요청 헤더 (토스페이먼츠 발송)

| 헤더 | 설명 |
|---|---|
| `tosspayments-webhook-transmission-id` | 전송 고유 ID. 멱등성 키로 활용 |
| `tosspayments-webhook-signature` | 이벤트 무결성 검증용 서명 |

#### Webhook 이벤트 본문

| 필드 | 타입 | 설명 |
|---|---|---|
| `eventType` | string | 이벤트 타입. 현재 `PAYMENT_STATUS_CHANGED` |
| `createdAt` | string (ISO 8601) | 이벤트 발생 시각 |
| `data.paymentKey` | string | 토스페이먼츠 결제 키 |
| `data.orderId` | string | 주문 고유 ID |
| `data.status` | string | 결제 상태. `DONE` · `ABORTED` · `EXPIRED` |
| `data.totalAmount` | integer | 결제 금액 (원 단위) |
| `data.approvedAt` | string (ISO 8601) | 승인 시각 (`DONE` 상태일 때만 존재) |

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

#### Webhook 처리 로직

| 수신 `data.status` | 처리 내용 |
|---|---|
| `DONE` | `POST /payments/confirm` 이후 2차 보정 처리. ORDER.status가 이미 `PAID`인 경우 멱등성에 의해 무시 |
| `ABORTED` | ORDER.status = `FAILED` 로 변경 + Redis 재고 복구 |
| `EXPIRED` | ORDER.status = `FAILED` 로 변경 + Redis 재고 복구 |

#### Response `200 OK`

Webhook 수신 및 처리 완료를 토스페이먼츠 서버에 알립니다. 200 이외의 응답은 토스페이먼츠가 재전송을 시도합니다.

---

## 11. 대기열 API

### 11.1 `POST /queue/enter` — 대기열 진입

세일 이벤트 대기열에 진입하여 구매 순서를 부여받습니다.

- **인증**: 필수 (USER)
- **관련 유스케이스**: UC-19
- **동작**: Redis Sorted Set에 `score = System.currentTimeMillis()`로 등록하여 진입 순서를 보장합니다.

#### Request Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `saleEventId` | integer | 필수 | 진입할 세일 이벤트 ID |

```json
{
  "saleEventId": 10
}
```

#### Response `200 OK`

| 필드 | 타입 | 설명 |
|---|---|---|
| `rank` | integer | 현재 대기 순번 (1부터 시작) |
| `estimatedWaitSeconds` | integer | 예상 대기 시간 (초 단위) |
| `queueToken` | string | 대기열 통과 후 `POST /orders` 요청 시 사용할 토큰 |

```json
{
  "rank": 1523,
  "estimatedWaitSeconds": 76,
  "queueToken": "queue-token-abc"
}
```

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `401` | `UNAUTHORIZED` | 인증 토큰 누락 또는 만료 |
| `404` | `SALE_EVENT_NOT_FOUND` | 존재하지 않는 세일 이벤트 ID |

---

### 11.2 `GET /queue/status` — 대기 순번 조회

현재 대기 순번과 구매 가능 여부를 조회합니다.

- **인증**: 필수 (USER)
- **관련 유스케이스**: UC-19
- **구매 가능 조건**: `rank = 0` 이면 즉시 구매 가능. `POST /orders` 요청 시 `X-Queue-Token` 헤더에 `queueToken` 포함

#### Query Parameters

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `saleEventId` | integer (long) | 필수 | 확인할 세일 이벤트 ID |
| `queueToken` | string | 필수 | `POST /queue/enter` 응답의 `queueToken` 값 |

#### Response `200 OK`

| 필드 | 타입 | 설명 |
|---|---|---|
| `rank` | integer | 현재 대기 순번. `0`이면 구매 가능 상태 |
| `canPurchase` | boolean | 구매 가능 여부 (`rank = 0`이면 `true`) |
| `estimatedWaitSeconds` | integer | 예상 잔여 대기 시간 (초 단위). 구매 가능 시 `0` |

```json
{
  "rank": 0,
  "canPurchase": true,
  "estimatedWaitSeconds": 0
}
```

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `401` | `UNAUTHORIZED` | 인증 토큰 누락 또는 만료 |
| `401` | `INVALID_QUEUE_TOKEN` | 유효하지 않거나 만료된 대기열 토큰 |

---

## 12. 정산 API

### 12.1 `GET /admin/settlements` — 정산 목록 조회

세일 이벤트별 정산 내역을 조회합니다.

- **인증**: 필수 (ADMIN)
- **관련 유스케이스**: UC-14
- **동작**: 세일 종료 후 Spring Batch로 정산 집계. 집계 결과는 Redis에 캐싱되어 빠른 조회를 지원합니다.

#### Query Parameters

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `from` | string (yyyy-MM-dd) | 선택 | 조회 시작일 |
| `to` | string (yyyy-MM-dd) | 선택 | 조회 종료일 |
| `status` | string | 선택 | 정산 상태 필터. `CALCULATING` · `DONE` |

#### Response `200 OK`

| 필드 | 타입 | 설명 |
|---|---|---|
| `data` | array | 정산 목록 |
| `data[].saleEventId` | integer | 세일 이벤트 고유 ID |
| `data[].totalOrders` | integer | 총 결제 완료 주문 수 |
| `data[].totalAmount` | integer | 총 결제 금액 (원 단위) |
| `data[].refundAmount` | integer | 총 환불 금액 (원 단위) |
| `data[].netAmount` | integer | 순 정산 금액 (`totalAmount - refundAmount`) |
| `data[].status` | string | 정산 상태. `CALCULATING` · `DONE` |
| `data[].periodFrom` | string (ISO 8601) | 정산 기간 시작 시각 |
| `data[].periodTo` | string (ISO 8601) | 정산 기간 종료 시각 |

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

#### 에러 코드

| HTTP 상태 | 에러 코드 | 설명 |
|---|---|---|
| `400` | `INVALID_INPUT` | 날짜 형식 오류 또는 `from`이 `to`보다 늦은 경우 |
| `403` | `FORBIDDEN` | ADMIN 권한 없음 |

---

## 13. 시스템 API

### 13.1 `GET /system/time` — 서버 시간 조회

서버의 현재 Unix 타임스탬프를 반환합니다.

- **인증**: 불필요
- **관련 유스케이스**: UC-23

> **프론트엔드 활용 방법**: 페이지 로드 시 이 API를 호출하여 `offset = serverTime - Date.now()` 값을 저장합니다. 이후 모든 시간 계산에 `Date.now() + offset`을 사용하면 사용자 PC 시계 오차와 무관하게 세일 정각 오픈 시 구매 버튼을 정확히 활성화할 수 있습니다.

#### Response `200 OK`

| 필드 | 타입 | 설명 |
|---|---|---|
| `serverTime` | integer (long) | 서버 현재 시각 (Unix 타임스탬프, 밀리초 단위) |
| `timezone` | string | 서버 타임존 |

```json
{
  "serverTime": 1735689600000,
  "timezone": "Asia/Seoul"
}
```

---

## 14. 실시간 재고 알림 (SSE)

### 14.1 `GET /products/stock-stream` — 전역 재고 푸시 (SSE)

특정 세일 이벤트의 재고 변화를 Server-Sent Events로 실시간 수신합니다.

- **인증**: 불필요
- **관련 유스케이스**: UC-24
- **Content-Type**: `text/event-stream`
- **동작**: 특정 상품 재고가 임계치 이하 또는 품절 상태가 되면 해당 이벤트를 구독 중인 모든 접속자에게 즉시 푸시합니다. 클라이언트는 수신 즉시 버튼 상태를 '품절'로 변경하거나 잔여 수량 뱃지를 업데이트합니다.

#### Query Parameters

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `saleEventId` | integer (long) | 필수 | 구독할 세일 이벤트 ID |

#### SSE 이벤트

**이벤트: `STOCK_UPDATED`** — 재고 임계치 이하 도달 시 발행

| 필드 | 타입 | 설명 |
|---|---|---|
| `saleEventId` | integer | 세일 이벤트 고유 ID |
| `remainingStock` | integer | 현재 잔여 재고 수량 |
| `isSoldOut` | boolean | 품절 여부. `false` |

```
event: STOCK_UPDATED
data: {"saleEventId": 10, "remainingStock": 5, "isSoldOut": false}
```

**이벤트: `STOCK_SOLD_OUT`** — 품절 시 발행

| 필드 | 타입 | 설명 |
|---|---|---|
| `saleEventId` | integer | 세일 이벤트 고유 ID |
| `remainingStock` | integer | 잔여 재고 수량. 항상 `0` |
| `isSoldOut` | boolean | 품절 여부. `true` |

```
event: STOCK_SOLD_OUT
data: {"saleEventId": 10, "remainingStock": 0, "isSoldOut": true}
```

---

## 15. 프론트엔드 처리 흐름

이 섹션은 백엔드 API 엔드포인트가 아닌 프론트엔드 전용 라우트 및 처리 흐름입니다.

### 결제 결과 랜딩 페이지 (UC-26)

**프론트엔드 경로**: `/order/success`

토스페이먼츠 결제창에서 결제 완료 후 브라우저가 자동으로 이 URL로 리다이렉트됩니다.

#### URL 쿼리 파라미터 (토스페이먼츠 전달)

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `paymentKey` | string | 토스페이먼츠 결제 키 |
| `orderId` | string | 주문 고유 ID |
| `amount` | integer | 결제 금액 |

예시 URL:
```
/order/success?paymentKey=tgen_20240101_abc123&orderId=order-uuid-1234&amount=29000
```

#### 페이지 처리 흐름

```
1. 페이지 마운트
   URL 쿼리 파라미터(paymentKey, orderId, amount) 파싱

2. POST /payments/confirm 자동 호출
   파싱한 파라미터를 Request Body로 전달

3. 처리 결과에 따라 사용자 메시지 표시
```

#### 처리 결과별 메시지

| 응답 상태 | 에러 코드 | 사용자 표시 메시지 |
|---|---|---|
| `200 OK` | - | "구매가 완료되었습니다!" |
| `400` | `ORDER_EXPIRED` | "재고 선점 시간이 초과되었습니다." |
| `400` | `PAYMENT_AMOUNT_MISMATCH` | "결제 금액이 일치하지 않습니다." |
| `500` | `TOSS_PAYMENTS_ERROR` | "결제 처리 중 오류가 발생했습니다." |

> 오류 발생 시 사용자에게 재시도 또는 고객센터 안내 버튼을 함께 제공하는 것을 권장합니다.
