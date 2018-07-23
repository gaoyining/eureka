/*
 * Copyright 2015 Netflix, Inc.
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

package com.netflix.eureka.util.batcher;

import com.netflix.eureka.util.batcher.TaskProcessor.ProcessingResult;

/**
 * {@link TrafficShaper} provides admission control policy prior to dispatching tasks to workers.
 * It reacts to events coming via reprocess requests (transient failures, congestion), and delays the processing
 * depending on this feedback.
 *
 * 网络通信整形器。当任务执行发生请求限流，或是请求网络失败的情况，
 * 则延时 将任务提交到工作任务队列，从而避免任务很快去执行，再次发生上述情况。
 *
 * @author Tomasz Bak
 */
class TrafficShaper {

    /**
     * Upper bound on delay provided by configuration.
     * 配置提供的延迟上限。
     */
    private static final long MAX_DELAY = 30 * 1000;

    /**
     * 请求限流延迟重试时间，单位：毫秒
     */
    private final long congestionRetryDelayMs;
    /**
     * 网络失败延迟重试时长，单位：毫秒
     */
    private final long networkFailureRetryMs;

    /**
     * 最后请求限流时间戳，单位：毫秒
     */
    private volatile long lastCongestionError;
    /**
     * 最后网络失败时间戳，单位：毫秒
     */
    private volatile long lastNetworkFailure;

    TrafficShaper(long congestionRetryDelayMs, long networkFailureRetryMs) {
        this.congestionRetryDelayMs = Math.min(MAX_DELAY, congestionRetryDelayMs);
        this.networkFailureRetryMs = Math.min(MAX_DELAY, networkFailureRetryMs);
    }

    /**
     * 注册失败
     *
     * @param processingResult
     */
    void registerFailure(ProcessingResult processingResult) {
        if (processingResult == ProcessingResult.Congestion) {
            // 拥挤错误，设置最后请求限流时间戳
            lastCongestionError = System.currentTimeMillis();
        } else if (processingResult == ProcessingResult.TransientError) {
            // 瞬时错误，最后网络失败时间戳
            lastNetworkFailure = System.currentTimeMillis();
        }
    }

    /**
     * 计算提交延迟，单位：毫秒
     *
     * @return 延迟
     */
    long transmissionDelay() {
        if (lastCongestionError == -1 && lastNetworkFailure == -1) {
            // 没有则返回0
            return 0;
        }

        long now = System.currentTimeMillis();

        // 计算最后请求限流带来的延迟
        if (lastCongestionError != -1) {
            // 阻塞延迟 = 当前时间 - 最后请求限流时间戳
            long congestionDelay = now - lastCongestionError;
            if (congestionDelay >= 0 && congestionDelay < congestionRetryDelayMs) {
                // 相当于还有多长时间再进行重试
                return congestionRetryDelayMs - congestionDelay;
            }
            // 重置时间戳
            lastCongestionError = -1;
        }

        // 计算最后网络失败带来的延迟
        if (lastNetworkFailure != -1) {
            // 失败延迟 = 当前时间 - 最后网络失败时间戳
            long failureDelay = now - lastNetworkFailure;
            if (failureDelay >= 0 && failureDelay < networkFailureRetryMs) {
                // 相当于还有多长时间再进行重试
                return networkFailureRetryMs - failureDelay;
            }
            // 重置时间戳
            lastNetworkFailure = -1;
        }
        // 无延迟，无错误
        return 0;
    }
}
