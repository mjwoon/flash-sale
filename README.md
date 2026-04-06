# 🛍️ Flash Sale — 선착순 한정 재고 구매 시스템

> 올리브영 세일, 티켓팅처럼 대규모 트래픽이 몰리는 환경에서  
> **재고 정합성 보장**과 **동시성 제어**를 직접 설계하고 구현한 포트폴리오 프로젝트입니다.

---

## 📌 프로젝트 배경

올리브영 세일 때 버튼을 눌렀는데 재고가 있다고 떠서 결제했더니 품절이라고 뜨는 경험을 했습니다.
왜 이런 일이 생기는지 분석하고, **동시 요청 환경에서 재고 정합성을 보장하는 시스템**을 직접 설계했습니다.

---

## 🔥 핵심 기술 챌린지

| 문제 | 해결 방법 |
|------|-----------|
| 동시 요청으로 인한 재고 Race Condition | **Redis Lua Script** (Get-Check-Set 원자적 처리) |
| 결제 이탈 시 재고 누수 | **Redis TTL + Keyspace Notification** (5분 자동 만료) |
| Kafka 메시지 중복 소비 | **Idempotency Key** (주문 중복 생성 방지) |
| Redis-DB 재고 불일치 | **Reconciliation Batch** (주기적 정합성 감사) |
| 외부 API 장애 전파 | **Circuit Breaker** (Resilience4j) |
| 트래픽 폭주 시 DB 과부하 | **Kafka 비동기 처리** + **Virtual Waiting Room** |

---

## 🏗️ 시스템 아키텍처

```
사용자 요청
    ↓
[React + Vite]  ──SSE──▶  실시간 주문 상태 / 재고 알림
    ↓
[Spring Boot API]
    ├── Redis Lua Script  ──▶  재고 선점 (원자적)
    ├── Redis SETNX       ──▶  중복 요청 차단
    ├── Redis Sorted Set  ──▶  대기열 순번 관리
    └── Kafka Producer    ──▶  구매 이벤트 발행
              ↓
    [Kafka Consumer]
    ├── MySQL  ──▶  주문 / 결제 / 정산 저장
    └── DLQ    ──▶  실패 메시지 격리 및 재처리
              ↓
    [토스페이먼츠]  ──Webhook──▶  결제 상태 동기화
```

---

## 🛠️ 기술 스택

### Backend
| 분류 | 기술 |
|------|------|
| Language | Java 21 |
| Framework | Spring Boot 3.5.x |
| Build | Gradle (Groovy DSL) |
| ORM | Spring Data JPA / Hibernate |
| DB | MySQL 8.0 |
| Cache / Lock | Redis 7.2 (Redisson) |
| Message Queue | Apache Kafka (KRaft) |
| Circuit Breaker | Resilience4j |
| Auth | Spring Security + JWT (jjwt) |
| Payment | 토스페이먼츠 API |

### Frontend
| 분류 | 기술 |
|------|------|
| Language | TypeScript |
| Framework | React 18 + Vite |
| State | Zustand + TanStack Query |
| Routing | React Router v6 |
| Style | Tailwind CSS |
| HTTP | Axios |

### Infra / DevOps
| 분류 | 기술 |
|------|------|
| Container | Docker + Docker Compose |
| Monitoring | Prometheus + Grafana |
| Load Test | k6 |

### Test
| 분류 | 기술 |
|------|------|
| Unit Test | JUnit 5 + Mockito |
| 동시성 Test | JUnit 5 + ExecutorService |
| Integration Test | Testcontainers (MySQL, Kafka, Redis) |

---

## 📁 프로젝트 구조

```
flash-sale/
├── backend/                    # Spring Boot (Java)
│   ├── src/
│   │   ├── main/java/com/flashsale/
│   │   └── resources/
│   │       ├── application.yml
│   │       └── application-docker.yml
│   ├── build.gradle
│   └── Dockerfile
├── frontend/                   # React + TypeScript + Vite
│   ├── src/
│   │   ├── api/
│   │   ├── components/
│   │   ├── hooks/
│   │   ├── pages/
│   │   └── types/
│   ├── vite.config.ts
│   └── Dockerfile
├── docker/
│   ├── mysql/init.sql
│   ├── redis/redis.conf
│   ├── prometheus/prometheus.yml
│   └── grafana/provisioning/
├── docker-compose.yml
└── .env.example
```

---

## 🚀 빠른 시작

### 사전 준비

- JDK 21
- Node.js 20
- Docker Desktop

### 실행

```bash
# 1. 저장소 클론
git clone https://github.com/{username}/flash-sale.git
cd flash-sale

# 2. 환경변수 설정
cp .env.example .env
# .env 파일을 열어 값을 채워주세요

# 3. 전체 서비스 실행
docker compose up --build
```

### 서비스 접속

| 서비스 | URL |
|--------|-----|
| Frontend | http://localhost:5173 |
| Backend API | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Kafka UI | http://localhost:8989 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |

### 개발 환경 (추천)

```bash
# 인프라만 Docker로 실행
docker compose up mysql redis kafka kafka-ui -d

# 백엔드 단독 실행 (IDE 또는 터미널)
cd backend
./gradlew bootRun

# 프론트엔드 단독 실행
cd frontend
npm install && npm run dev
```

---

## 📐 핵심 구현 포인트

### 1. Redis Lua Script — 재고 원자적 차감 (UC-15)

단순 `DECR`은 음수까지 내려가는 문제가 있습니다.
`GET → 조건 체크 → SET`을 Lua Script로 묶어 **원자성**을 보장합니다.

```lua
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil or stock <= 0 then
    return -1  -- 재고 없음
end
redis.call('DECR', KEYS[1])
return stock - 1
```

### 2. 결제 타임아웃 자동 재고 복구 (UC-06)

선점 키에 TTL(5분)을 설정하고, Redis Keyspace Notification으로 만료 이벤트를 수신해 재고를 자동 복구합니다. 별도 스케줄러 없이 이벤트 기반으로 처리합니다.

```
redis.conf
  notify-keyspace-events Ex  ← 핵심 설정
```

### 3. 동시성 테스트 — Race Condition 검증

```java
@Test
void 동시에_100명이_요청해도_재고는_정확히_차감된다() {
    // Redis 적용 전: 실패 (재고 불일치 발생)
    // Redis 적용 후: 통과 (정확히 차감)
    int stock = 10;
    CountDownLatch latch = new CountDownLatch(100);

    for (int i = 0; i < 100; i++) {
        executorService.submit(() -> {
            purchaseService.purchase("product-A");
            latch.countDown();
        });
    }
    latch.await();

    assertThat(stockService.getStock("product-A")).isEqualTo(0);
    assertThat(purchaseService.getSuccessCount("product-A")).isEqualTo(stock);
}
```

### 4. Outbox Pattern — Kafka 메시지 신뢰성 보장 (UC-16)

DB 트랜잭션과 Kafka 발행을 원자적으로 처리하기 위해 먼저 `OUTBOX` 테이블에 이벤트를 저장하고,
별도 publisher가 Kafka로 발행합니다.

```
ORDER 저장 + OUTBOX 저장  ← 하나의 트랜잭션
         ↓
  OUTBOX Publisher
         ↓
    Kafka 발행
```

---

## 📊 Use Case 목록

| 분류 | UC | 핵심 기술 |
|------|----|-----------|
| 사용자 | UC-01 ~ UC-07 | Redis Cache, Lua Script, SSE, Cursor Pagination, Saga |
| 관리자 | UC-08 ~ UC-14 | Cache Invalidation, Feature Flag, Prometheus Alert |
| 시스템 | UC-15 ~ UC-22 | Lua Script, Kafka, Idempotency, DLQ, Circuit Breaker |
| 풀스택 | UC-23 ~ UC-26 | 서버시간 동기화, SSE 전역 재고 Push, JWT Auth, 결제 랜딩 |

---

## 🗃️ ERD

10개 테이블로 구성됩니다.

```
USER ──< ORDERS >── SALE_EVENT >── PRODUCT
              |           |
           PAYMENT    WAITING_QUEUE
              |           |
         STOCK_HISTORY  SETTLEMENT
              |
           OUTBOX
              
CIRCUIT_BREAKER_LOG (독립)
```

