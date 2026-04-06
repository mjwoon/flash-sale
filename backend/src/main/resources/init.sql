-- Flash Sale Database Schema
-- MySQL 8.0

CREATE DATABASE IF NOT EXISTS flash_sale
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE flash_sale;

CREATE TABLE `user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `email`         VARCHAR(255) NOT NULL,
    `name`          VARCHAR(100) NOT NULL,
    `phone`         VARCHAR(20)  NULL,
    `password`      VARCHAR(255) NOT NULL,
    `refresh_token` VARCHAR(512) NULL,
    `role`          ENUM('USER', 'ADMIN') NOT NULL DEFAULT 'USER',
    `created_at`    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                          ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_user_email` (`email`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `product` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(255) NOT NULL,
    `description` TEXT         NULL,
    `price`       INT          NOT NULL,
    `is_deleted`  TINYINT(1)   NOT NULL DEFAULT 0
                               COMMENT 'UC-09 Soft Delete',
    `created_at`  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    INDEX `idx_product_is_deleted` (`is_deleted`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `sale_event` (
    `id`             BIGINT      NOT NULL AUTO_INCREMENT,
    `product_id`     BIGINT      NOT NULL,
    `total_stock`    INT         NOT NULL COMMENT '초기 등록 재고 (불변)',
    `reserved_stock` INT         NOT NULL DEFAULT 0
                                 COMMENT '선점 중인 수량 (결제 대기) — UC-02',
    `sold_stock`     INT         NOT NULL DEFAULT 0
                                 COMMENT '결제 완료된 수량',
    `status`         ENUM('SCHEDULED', 'ACTIVE', 'ENDED', 'HALTED')
                                 NOT NULL DEFAULT 'SCHEDULED',
    `is_halted`      TINYINT(1)  NOT NULL DEFAULT 0
                                 COMMENT 'UC-12 Feature Flag 긴급 중단',
    `starts_at`      DATETIME(6) NOT NULL COMMENT 'UC-10 예약 오픈',
    `ends_at`        DATETIME(6) NOT NULL COMMENT 'UC-10 예약 종료',
    `created_at`     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    INDEX `idx_sale_event_product_id` (`product_id`),
    INDEX `idx_sale_event_status` (`status`),
    INDEX `idx_sale_event_starts_at` (`starts_at`),
    CONSTRAINT `fk_sale_event_product`
        FOREIGN KEY (`product_id`) REFERENCES `product` (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `waiting_queue` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT,
    `sale_event_id` BIGINT      NOT NULL,
    `user_id`       BIGINT      NOT NULL,
    `status`        ENUM('WAITING', 'ENTERED', 'EXPIRED')
                                NOT NULL DEFAULT 'WAITING',
    `entered_at`    DATETIME(6) NULL     COMMENT '진입 성공 시각',
    `created_at`    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    INDEX `idx_wq_sale_event_id` (`sale_event_id`),
    INDEX `idx_wq_user_id` (`user_id`),
    INDEX `idx_wq_status` (`status`),
    CONSTRAINT `fk_wq_sale_event`
        FOREIGN KEY (`sale_event_id`) REFERENCES `sale_event` (`id`),
    CONSTRAINT `fk_wq_user`
        FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `orders` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT,
    `idempotency_key` VARCHAR(64) NOT NULL COMMENT 'UC-17 Kafka 중복 방지',
    `user_id`         BIGINT      NOT NULL,
    `sale_event_id`   BIGINT      NOT NULL,
    `product_id`      BIGINT      NOT NULL,
    `quantity`        INT         NOT NULL DEFAULT 1,
    `total_amount`    INT         NOT NULL,
    `status`          ENUM('PENDING', 'PAID', 'FAILED', 'CANCELLED', 'EXPIRED')
                                  NOT NULL DEFAULT 'PENDING',
    `expires_at`      DATETIME(6) NOT NULL
                                  COMMENT 'UC-06 결제 타임아웃 기준 (5분)',
    `created_at`      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                           ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_orders_idempotency_key` (`idempotency_key`),
    INDEX `idx_orders_user_id` (`user_id`),
    INDEX `idx_orders_sale_event_id` (`sale_event_id`),
    INDEX `idx_orders_status` (`status`),
    INDEX `idx_orders_expires_at` (`expires_at`),
    -- UC-05 커서 페이지네이션 커버링 인덱스
    INDEX `idx_orders_user_status_id` (`user_id`, `status`, `id`),
    CONSTRAINT `fk_orders_user`
        FOREIGN KEY (`user_id`) REFERENCES `user` (`id`),
    CONSTRAINT `fk_orders_sale_event`
        FOREIGN KEY (`sale_event_id`) REFERENCES `sale_event` (`id`),
    CONSTRAINT `fk_orders_product`
        FOREIGN KEY (`product_id`) REFERENCES `product` (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `payment` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `order_id`        BIGINT       NOT NULL,
    `payment_key`     VARCHAR(200) NULL     COMMENT '토스페이먼츠 발급 키',
    `idempotency_key` VARCHAR(64)  NOT NULL COMMENT 'UC-03 Webhook 멱등성',
    `amount`          INT          NOT NULL,
    `status`          ENUM('PENDING', 'DONE', 'FAILED', 'CANCELLED')
                                   NOT NULL DEFAULT 'PENDING',
    `failure_reason`  VARCHAR(500) NULL,
    `approved_at`     DATETIME(6)  NULL,
    `created_at`      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_payment_payment_key` (`payment_key`),
    UNIQUE INDEX `uk_payment_idempotency_key` (`idempotency_key`),
    INDEX `idx_payment_order_id` (`order_id`),
    INDEX `idx_payment_status` (`status`),
    CONSTRAINT `fk_payment_order`
        FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `stock_history` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `sale_event_id` BIGINT       NOT NULL,
    `order_id`      BIGINT       NULL
                                 COMMENT '주문 없이 발생하는 케이스 대비 NULL 허용',
    `action`        ENUM('RESERVE', 'RELEASE', 'DEDUCT')
                                 NOT NULL,
    `delta`         INT          NOT NULL
                                 COMMENT '변동 수량 (양수: 증가, 음수: 감소)',
    `triggered_by`  VARCHAR(100) NULL
                                 COMMENT 'UC-06 TIMEOUT, UC-07 CANCEL 등',
    `created_at`    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    INDEX `idx_stock_history_sale_event_id` (`sale_event_id`),
    INDEX `idx_stock_history_order_id` (`order_id`),
    INDEX `idx_stock_history_created_at` (`created_at`),
    CONSTRAINT `fk_stock_history_sale_event`
        FOREIGN KEY (`sale_event_id`) REFERENCES `sale_event` (`id`),
    CONSTRAINT `fk_stock_history_order`
        FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `settlement` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT,
    `sale_event_id` BIGINT      NOT NULL,
    `order_id_from` BIGINT      NULL COMMENT '정산 기준 첫 주문 ID (Audit용)',
    `order_id_to`   BIGINT      NULL COMMENT '정산 기준 마지막 주문 ID (Audit용)',
    `period_from`   DATETIME(6) NULL COMMENT '정산 대상 시작 시각',
    `period_to`     DATETIME(6) NULL COMMENT '정산 대상 종료 시각',
    `total_orders`  INT         NOT NULL DEFAULT 0,
    `total_amount`  INT         NOT NULL DEFAULT 0,
    `refund_amount` INT         NOT NULL DEFAULT 0,
    `net_amount`    INT         NOT NULL DEFAULT 0
                                COMMENT 'UC-14 순수익 = total - refund',
    `status`        ENUM('CALCULATING', 'DONE')
                                NOT NULL DEFAULT 'CALCULATING',
    `calculated_at` DATETIME(6) NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_settlement_sale_event_id` (`sale_event_id`),
    CONSTRAINT `fk_settlement_sale_event`
        FOREIGN KEY (`sale_event_id`) REFERENCES `sale_event` (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `outbox` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `aggregate_id`   BIGINT       NOT NULL COMMENT '연관 엔티티 ID (order_id 등)',
    `aggregate_type` VARCHAR(50)  NOT NULL COMMENT 'ORDER, PAYMENT 등',
    `event_type`     VARCHAR(100) NOT NULL COMMENT 'ORDER_CREATED, ORDER_PAID 등',
    `payload`        JSON         NOT NULL,
    `status`         ENUM('PENDING', 'PUBLISHED', 'FAILED')
                                  NOT NULL DEFAULT 'PENDING',
    `retry_count`    INT          NOT NULL DEFAULT 0
                                  COMMENT 'UC-20 DLQ 재처리 횟수',
    `published_at`   DATETIME(6)  NULL,
    `created_at`     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    INDEX `idx_outbox_status` (`status`),
    INDEX `idx_outbox_aggregate` (`aggregate_type`, `aggregate_id`),
    INDEX `idx_outbox_created_at` (`created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `circuit_breaker_log` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `target`           VARCHAR(100) NOT NULL COMMENT 'REDIS, TOSS_PAYMENTS 등',
    `request_path`     VARCHAR(255) NULL     COMMENT '장애 발생 API 경로',
    `state`            ENUM('CLOSED', 'OPEN', 'HALF_OPEN')
                                    NOT NULL,
    `failure_count`    INT          NOT NULL DEFAULT 0,
    `success_count`    INT          NOT NULL DEFAULT 0,
    `error_message`    VARCHAR(500) NULL,
    `error_code`       VARCHAR(50)  NULL     COMMENT 'HTTP 상태코드 등',
    `state_changed_at` DATETIME(6)  NOT NULL COMMENT 'UC-22',
    `created_at`       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    INDEX `idx_cb_log_target` (`target`),
    INDEX `idx_cb_log_state` (`state`),
    INDEX `idx_cb_log_created_at` (`created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
