package com.netflix.eureka.util.batcher;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.netflix.eureka.util.batcher.TaskProcessor.ProcessingResult;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.StatsTimer;
import com.netflix.servo.monitor.Timer;
import com.netflix.servo.stats.StatsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.eureka.Names.METRIC_REPLICATION_PREFIX;

/**
 * An active object with an internal thread accepting tasks from clients, and dispatching them to
 * workers in a pull based manner. Workers explicitly request an item or a batch of items whenever they are
 * available. This guarantees that data to be processed are always up to date, and no stale data processing is done.
 *
 * <h3>Task identification</h3>
 * Each task passed for processing has a corresponding task id. This id is used to remove duplicates (replace
 * older copies with newer ones).
 *
 * <h3>Re-processing</h3>
 * If data processing by a worker failed, and the failure is transient in nature, the worker will put back the
 * task(s) back to the {@link AcceptorExecutor}. This data will be merged with current workload, possibly discarded if
 * a newer version has been already received.
 *
 * 任务接收器
 *
 * @author Tomasz Bak
 */
class AcceptorExecutor<ID, T> {

    private static final Logger logger = LoggerFactory.getLogger(AcceptorExecutor.class);

    /**
     * 待执行队列最大数量
     */
    private final int maxBufferSize;
    /**
     * 单个批量任务包含任务最大数量
     */
    private final int maxBatchingSize;
    /**
     * 批量任务等待最大延迟时长，单位：毫秒
     */
    private final long maxBatchingDelay;

    /**
     * 是否关闭
     */
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    /**
     * 接收任务队列
     */
    private final BlockingQueue<TaskHolder<ID, T>> acceptorQueue = new LinkedBlockingQueue<>();
    /**
     * 重新执行任务队列
     */
    private final BlockingDeque<TaskHolder<ID, T>> reprocessQueue = new LinkedBlockingDeque<>();
    /**
     * 接收任务线程
     */
    private final Thread acceptorThread;

    /**
     * 待执行任务映射
     */
    private final Map<ID, TaskHolder<ID, T>> pendingTasks = new HashMap<>();
    /**
     * 待执行队列
     */
    private final Deque<ID> processingOrder = new LinkedList<>();

    /**
     * 单任务工作请求信号量
     */
    private final Semaphore singleItemWorkRequests = new Semaphore(0);
    /**
     * 单任务工作队列
     */
    private final BlockingQueue<TaskHolder<ID, T>> singleItemWorkQueue = new LinkedBlockingQueue<>();

    /**
     * 批量任务工作请求信号量
     */
    private final Semaphore batchWorkRequests = new Semaphore(0);
    /**
     * 批量任务工作队列
     */
    private final BlockingQueue<List<TaskHolder<ID, T>>> batchWorkQueue = new LinkedBlockingQueue<>();

    /**
     * 网络通信整形器
     */
    private final TrafficShaper trafficShaper;

    /*
     * Metrics
     */
    @Monitor(name = METRIC_REPLICATION_PREFIX + "acceptedTasks", description = "Number of accepted tasks", type = DataSourceType.COUNTER)
    volatile long acceptedTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "replayedTasks", description = "Number of replayedTasks tasks", type = DataSourceType.COUNTER)
    volatile long replayedTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "expiredTasks", description = "Number of expired tasks", type = DataSourceType.COUNTER)
    volatile long expiredTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "overriddenTasks", description = "Number of overridden tasks", type = DataSourceType.COUNTER)
    volatile long overriddenTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "queueOverflows", description = "Number of queue overflows", type = DataSourceType.COUNTER)
    volatile long queueOverflows;

    private final Timer batchSizeMetric;

    AcceptorExecutor(String id,
                     int maxBufferSize,
                     int maxBatchingSize,
                     long maxBatchingDelay,
                     long congestionRetryDelayMs,
                     long networkFailureRetryMs) {
        this.maxBufferSize = maxBufferSize;
        this.maxBatchingSize = maxBatchingSize;
        this.maxBatchingDelay = maxBatchingDelay;
        // 创建 网络通信整形器
        this.trafficShaper = new TrafficShaper(congestionRetryDelayMs, networkFailureRetryMs);

        // 创建线程组
        ThreadGroup threadGroup = new ThreadGroup("eurekaTaskExecutors");
        this.acceptorThread = new Thread(threadGroup, new AcceptorRunner(), "TaskAcceptor-" + id);
        this.acceptorThread.setDaemon(true);
        this.acceptorThread.start();

        final double[] percentiles = {50.0, 95.0, 99.0, 99.5};
        final StatsConfig statsConfig = new StatsConfig.Builder()
                .withSampleSize(1000)
                .withPercentiles(percentiles)
                .withPublishStdDev(true)
                .build();
        final MonitorConfig config = MonitorConfig.builder(METRIC_REPLICATION_PREFIX + "batchSize").build();
        this.batchSizeMetric = new StatsTimer(config, statsConfig);
        try {
            Monitors.registerObject(id, this);
        } catch (Throwable e) {
            logger.warn("Cannot register servo monitor for this object", e);
        }
    }

    void process(ID id, T task, long expiryTime) {
        // 添加任务到接收任务队列
        acceptorQueue.add(new TaskHolder<ID, T>(id, task, expiryTime));
        acceptedTasks++;
    }

    void reprocess(List<TaskHolder<ID, T>> holders, ProcessingResult processingResult) {
        // 添加到 重新执行队列
        reprocessQueue.addAll(holders);
        replayedTasks += holders.size();
        // 提交任务结果给 TrafficShaper
        trafficShaper.registerFailure(processingResult);
    }

    void reprocess(TaskHolder<ID, T> taskHolder, ProcessingResult processingResult) {
        reprocessQueue.add(taskHolder);
        replayedTasks++;
        trafficShaper.registerFailure(processingResult);
    }

    BlockingQueue<TaskHolder<ID, T>> requestWorkItem() {
        singleItemWorkRequests.release();
        return singleItemWorkQueue;
    }

    BlockingQueue<List<TaskHolder<ID, T>>> requestWorkItems() {
        batchWorkRequests.release();
        return batchWorkQueue;
    }

    void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            acceptorThread.interrupt();
        }
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "acceptorQueueSize", description = "Number of tasks waiting in the acceptor queue", type = DataSourceType.GAUGE)
    public long getAcceptorQueueSize() {
        return acceptorQueue.size();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "reprocessQueueSize", description = "Number of tasks waiting in the reprocess queue", type = DataSourceType.GAUGE)
    public long getReprocessQueueSize() {
        return reprocessQueue.size();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "queueSize", description = "Task queue size", type = DataSourceType.GAUGE)
    public long getQueueSize() {
        return pendingTasks.size();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "pendingJobRequests", description = "Number of worker threads awaiting job assignment", type = DataSourceType.GAUGE)
    public long getPendingJobRequests() {
        return singleItemWorkRequests.availablePermits() + batchWorkRequests.availablePermits();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "availableJobs", description = "Number of jobs ready to be taken by the workers", type = DataSourceType.GAUGE)
    public long workerTaskQueueSize() {
        return singleItemWorkQueue.size() + batchWorkQueue.size();
    }

    /**
     * 执行线程
     */
    class AcceptorRunner implements Runnable {
        @Override
        public void run() {
            long scheduleTime = 0;
            while (!isShutdown.get()) {
                try {
                    // ----------------------------关键方法-------------------------
                    // 处理完输入队列( 接收队列 + 重新执行队列 )
                    drainInputQueues();

                    // 待执行队列size
                    int totalItems = processingOrder.size();

                    // 当前时间
                    long now = System.currentTimeMillis();
                    if (scheduleTime < now) {
                        // 任务时间 = 当前时间 + 任务延迟时间
                        scheduleTime = now + trafficShaper.transmissionDelay();
                    }
                    // 调度
                    if (scheduleTime <= now) {
                        // -------------------------关键方法--------------------------
                        // 调度批量任务
                        assignBatchWork();
                        // -------------------------关键方法--------------------------
                        // 调度单任务
                        assignSingleItemWork();
                    }

                    // If no worker is requesting data or there is a delay injected by the traffic shaper,
                    // sleep for some time to avoid tight loop.
                    // 如果没有工作人员请求数据或流量整形器注入了延迟，
                    // 睡一段时间以避免紧密循环
                    if (totalItems == processingOrder.size()) {
                        Thread.sleep(10);
                    }
                } catch (InterruptedException ex) {
                    // Ignore
                } catch (Throwable e) {
                    // Safe-guard, so we never exit this loop in an uncontrolled way.
                    logger.warn("Discovery AcceptorThread error", e);
                }
            }
        }

        private boolean isFull() {
            return pendingTasks.size() >= maxBufferSize;
        }

        /**
         * 处理完输入队列( 接收队列 + 重新执行队列 )
         * @throws InterruptedException
         */
        private void drainInputQueues() throws InterruptedException {
            do {
                // ------------------------关键方法---------------------
                // 处理完重新执行队列
                drainReprocessQueue();
                // ------------------------关键方法---------------------
                // 处理完接收队列
                drainAcceptorQueue();

                // 所有队列为空，等待 10 ms，看接收队列是否有新任务
                if (!isShutdown.get()) {
                    // If all queues are empty, block for a while on the acceptor queue
                    // 如果所有队列都为空，请在接受队列上暂停一段时间
                    if (reprocessQueue.isEmpty() && acceptorQueue.isEmpty() && pendingTasks.isEmpty()) {
                        TaskHolder<ID, T> taskHolder = acceptorQueue.poll(10, TimeUnit.MILLISECONDS);
                        if (taskHolder != null) {
                            // 如果获得到了，将任务添加到待执行队列
                            appendTaskHolder(taskHolder);
                        }
                    }
                }
            } while (!reprocessQueue.isEmpty() || !acceptorQueue.isEmpty() || pendingTasks.isEmpty());
        }

        private void drainAcceptorQueue() {
            while (!acceptorQueue.isEmpty()) {
                // ---------------------关键方法---------------------
                // 循环，直到接收队列为空
                appendTaskHolder(acceptorQueue.poll());
            }
        }

        /**
         * 处理完重新执行队列
         */
        private void drainReprocessQueue() {
            long now = System.currentTimeMillis();
            while (!reprocessQueue.isEmpty() && !isFull()) {
                // 拿最后的任务，其实就是优先拿重新执行任务队列较新的任务
                TaskHolder<ID, T> taskHolder = reprocessQueue.pollLast();
                ID id = taskHolder.getId();
                if (taskHolder.getExpiryTime() <= now) {
                    // 过期
                    expiredTasks++;
                } else if (pendingTasks.containsKey(id)) {
                    // 已存在
                    overriddenTasks++;
                } else {
                    // 放入任务执行映射
                    pendingTasks.put(id, taskHolder);
                    // 提交到队头
                    processingOrder.addFirst(id);
                }
            }
            // 如果待执行队列已满，清空重新执行队列，放弃较早的任务
            if (isFull()) {
                queueOverflows += reprocessQueue.size();
                reprocessQueue.clear();
            }
        }

        /**
         * 如果获得到了，将任务添加到待执行队列
         * @param taskHolder
         */
        private void appendTaskHolder(TaskHolder<ID, T> taskHolder) {
            // 判断任务是否已经满了
            if (isFull()) {
                // 任务已经满了，移除最早的一个
                pendingTasks.remove(processingOrder.poll());
                queueOverflows++;
            }
            TaskHolder<ID, T> previousTask = pendingTasks.put(taskHolder.getId(), taskHolder);
            if (previousTask == null) {
                // 如果待执行任务队列中已经存在这个任务，则放入待执行队列
                processingOrder.add(taskHolder.getId());
            } else {
                overriddenTasks++;
            }
        }


        /**
         * 调度单任务
         */
        void assignSingleItemWork() {
            if (!processingOrder.isEmpty()) {
                // 待执行任队列不为空
                if (singleItemWorkRequests.tryAcquire(1)) {
                    // 获取 单任务工作请求信号量
                    long now = System.currentTimeMillis();
                    while (!processingOrder.isEmpty()) {
                        // 【循环】获取单任务
                        ID id = processingOrder.poll();
                        // 一定不为空
                        TaskHolder<ID, T> holder = pendingTasks.remove(id);
                        if (holder.getExpiryTime() > now) {
                            singleItemWorkQueue.add(holder);
                            return;
                        }
                        expiredTasks++;
                    }
                    // 获取不到单任务，释放请求信号量
                    singleItemWorkRequests.release();
                }
            }
        }

        void assignBatchWork() {
            // ------------------------关键方法--------------------------
            //判断是否有足够任务进行下一次批量任务调度：
            //  1）待执行任务( processingOrder )映射已满；
            //  2）到达批量任务处理最大等待延迟
            if (hasEnoughTasksForNextBatch()) {
                // 获取 批量任务工作请求信号量
                if (batchWorkRequests.tryAcquire(1)) {
                    // 获取批量任务
                    long now = System.currentTimeMillis();
                    // 取单个批量任务包含任务最大数量 和 待执行队列 的最小值
                    int len = Math.min(maxBatchingSize, processingOrder.size());
                    List<TaskHolder<ID, T>> holders = new ArrayList<>(len);
                    while (holders.size() < len && !processingOrder.isEmpty()) {
                        ID id = processingOrder.poll();
                        TaskHolder<ID, T> holder = pendingTasks.remove(id);
                        if (holder.getExpiryTime() > now) {
                            // 过期
                            holders.add(holder);
                        } else {
                            expiredTasks++;
                        }
                    }
                    if (holders.isEmpty()) {
                        // 未调度到批量任务，释放请求信号量
                        batchWorkRequests.release();
                    } else {
                        // 添加批量任务到批量任务工作队列
                        batchSizeMetric.record(holders.size(), TimeUnit.MILLISECONDS);
                        batchWorkQueue.add(holders);
                    }
                }
            }
        }

        private boolean hasEnoughTasksForNextBatch() {
            if (processingOrder.isEmpty()) {
                // 待执行队列为空，返回false
                return false;
            }
            if (pendingTasks.size() >= maxBufferSize) {
                // 待执行任务映射数 > 待执行队列最大数量，返回true
                return true;
            }

            // 获得待执行队列里面的第一个任务
            TaskHolder<ID, T> nextHolder = pendingTasks.get(processingOrder.peek());
            // 延迟时间 = 当前时间 - 第一个任务的提交时间
            long delay = System.currentTimeMillis() - nextHolder.getSubmitTimestamp();
            return delay >= maxBatchingDelay;
        }
    }
}
