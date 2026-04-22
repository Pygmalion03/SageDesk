/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.ingestion.service.impl;

import com.nageoffer.ai.ragent.ingestion.service.IngestionTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class IngestionTaskRecoveryStarter {

    private static final String RECOVERY_LOCK_KEY = "ingestion:recovery:start:lock";

    private static final long RECOVERY_LOCK_LEASE_MILLIS = TimeUnit.MINUTES.toMillis(2);

    private final IngestionTaskService ingestionTaskService;
    private final RedissonClient redissonClient;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasksOnStartup() {
        RLock lock = redissonClient.getLock(RECOVERY_LOCK_KEY);
        boolean locked = tryLock(lock);
        if (!locked) {
            log.info("Skip interrupted ingestion recovery because another instance holds the lock");
            return;
        }
        try {
            log.info("Starting interrupted ingestion task recovery");
            ingestionTaskService.recoverInterruptedTasks();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private boolean tryLock(RLock lock) {
        try {
            return lock.tryLock(0, RECOVERY_LOCK_LEASE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while acquiring ingestion recovery lock", ex);
            return false;
        }
    }
}
