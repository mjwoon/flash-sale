package com.flashsale.backend.service;

import com.flashsale.backend.exception.LockAcquisitionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisLockService {

    private static final String LOCK_PREFIX = "lock:";
    private static final long WAIT_TIME_SEC = 3L;
    private static final long LEASE_TIME_SEC = 5L;

    private final RedissonClient redissonClient;

    public <T> T executeWithLock(String lockKey, Supplier<T> task) {
        RLock lock = redissonClient.getLock(LOCK_PREFIX + lockKey);
        try {
            boolean acquired = lock.tryLock(WAIT_TIME_SEC, LEASE_TIME_SEC, TimeUnit.SECONDS);
            if (!acquired) {
                throw new LockAcquisitionException(lockKey);
            }
            log.debug("Lock acquired: {}", lockKey);
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException(lockKey, e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("Lock released: {}", lockKey);
            }
        }
    }
}
