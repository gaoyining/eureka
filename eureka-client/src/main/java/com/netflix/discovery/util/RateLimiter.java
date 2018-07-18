/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.discovery.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter implementation is based on token bucket algorithm. There are two parameters:
 * <ul>
 * <li>
 *     burst size - maximum number of requests allowed into the system as a burst
 * </li>
 * <li>
 *     average rate - expected number of requests per second (RateLimiters using MINUTES is also supported)
 * </li>
 * </ul>
 *
 * 速率限制器实现基于令牌桶算法。 有两个参数：
 * <ul>
 *      <li>
 *          突发大小 - 作为突发允许进入系统的最大请求数
 *      </ li>
 *      <li>
 *          平均速率 - 预期的每秒请求数（也支持使用MINUTES的RateLimiters）
 *      </ li>
 * </ ul>
 *
 * @author Tomasz Bak
 */
public class RateLimiter {

    /**
     * 速率单位转换成毫秒
     */
    private final long rateToMsConversion;

    /**
     * 消耗令牌数
     */
    private final AtomicInteger consumedTokens = new AtomicInteger();
    /**
     * 最后填充时间
     */
    private final AtomicLong lastRefillTime = new AtomicLong(0);

    @Deprecated
    public RateLimiter() {
        this(TimeUnit.SECONDS);
    }

    public RateLimiter(TimeUnit averageRateUnit) {
        switch (averageRateUnit) {
            case SECONDS:
                // 秒
                rateToMsConversion = 1000;
                break;
            case MINUTES:
                // 分钟
                rateToMsConversion = 60 * 1000;
                break;
            default:
                throw new IllegalArgumentException("TimeUnit of " + averageRateUnit + " is not supported");
        }
    }

    // RateLimiter.java
    /**
     * 获取令牌( Token )
     *
     * @param burstSize 令牌桶上限
     * @param averageRate 令牌再装平均速率
     * @return 是否获取成功
     */
    public boolean acquire(int burstSize, long averageRate) {
        return acquire(burstSize, averageRate, System.currentTimeMillis());
    }

    public boolean acquire(int burstSize, long averageRate, long currentTimeMillis) {
        if (burstSize <= 0 || averageRate <= 0) {
            // Instead of throwing exception, we just let all the traffic go
            // 而不是抛出异常，我们只是让所有的流量都去了
            return true;
        }

        // ----------------------关键方法---------------------------
        // 重新填充令牌
        refillToken(burstSize, averageRate, currentTimeMillis);
        // ----------------------关键方法---------------------------
        // 消费令牌
        return consumeToken(burstSize);
    }

    /**
     * 获取令牌( Token )
     *
     * @param burstSize 令牌桶上限
     * @param averageRate 令牌再装平均速率
     * @param currentTimeMillis 当前时间
     * @return 是否获取成功
     */
    private void refillToken(int burstSize, long averageRate, long currentTimeMillis) {
        // 最后的填充时间
        long refillTime = lastRefillTime.get();
        // 获得过去多少毫秒
        long timeDelta = currentTimeMillis - refillTime;

        // 计算可填充最大令牌数量
        long newTokens = timeDelta * averageRate / rateToMsConversion;
        if (newTokens > 0) {
            // 计算新的填充令牌的时间
            long newRefillTime = refillTime == 0
                    ? currentTimeMillis
                    : refillTime + newTokens * rateToMsConversion / averageRate;
            // 每次填充令牌，会设置 currentTimeMillis 到 refillTime
            if (lastRefillTime.compareAndSet(refillTime, newRefillTime)) {
                // CAS保证有且仅有一个线程进入填充
                while (true) {
                    // 计算填充令牌后的已消耗令牌数量
                    int currentLevel = consumedTokens.get();
                    // In case burstSize decreased
                    // 如果burstSize减少了

                    // 取计算填充令牌后的已消耗令牌数量和令牌桶的最小值，为令牌数量
                    int adjustedLevel = Math.min(currentLevel, burstSize);
                    // 取令牌数量减去计算可填充最大令牌数量
                    int newLevel = (int) Math.max(0, adjustedLevel  -newTokens);
                    if (consumedTokens.compareAndSet(currentLevel, newLevel)) {
                        return;
                    }
                }
            }
        }
    }

    private boolean consumeToken(int burstSize) {
        while (true) {
            // 木桶里没有令牌
            int currentLevel = consumedTokens.get();
            if (currentLevel >= burstSize) {
                return false;
            }
            // CAS 避免和正在消费令牌或者填充令牌的线程冲突
            if (consumedTokens.compareAndSet(currentLevel, currentLevel + 1)) {
                return true;
            }
        }
    }

    public void reset() {
        consumedTokens.set(0);
        lastRefillTime.set(0);
    }
}
