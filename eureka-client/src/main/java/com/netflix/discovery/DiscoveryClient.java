/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.discovery;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.netflix.appinfo.*;
import com.netflix.appinfo.InstanceInfo.ActionType;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.endpoint.EndpointUtils;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.resolver.ClosableResolver;
import com.netflix.discovery.shared.resolver.aws.ApplicationsResolver;
import com.netflix.discovery.shared.transport.*;
import com.netflix.discovery.shared.transport.jersey.EurekaJerseyClient;
import com.netflix.discovery.shared.transport.jersey.Jersey1DiscoveryClientOptionalArgs;
import com.netflix.discovery.shared.transport.jersey.Jersey1TransportClientFactories;
import com.netflix.discovery.shared.transport.jersey.TransportClientFactories;
import com.netflix.discovery.util.ThresholdLevelsMetric;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.annotation.PreDestroy;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.ws.rs.core.Response.Status;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.netflix.discovery.EurekaClientNames.METRIC_REGISTRATION_PREFIX;
import static com.netflix.discovery.EurekaClientNames.METRIC_REGISTRY_PREFIX;

/**
 * The class that is instrumental for interactions with <tt>Eureka Server</tt>.
 *
 * 与<TT> EURKA Server </TT>交互的类。
 *  -向 Eureka-Server 注册自身服务
 *  -向 Eureka-Server 续约自身服务
 *  -向 Eureka-Server 取消自身服务，当关闭时
 *  -从 Eureka-Server 查询应用集合和应用实例信息
 *
 * <p>
 * <tt>Eureka Client</tt> is responsible for a) <em>Registering</em> the
 * instance with <tt>Eureka Server</tt> b) <em>Renewal</em>of the lease with
 * <tt>Eureka Server</tt> c) <em>Cancellation</em> of the lease from
 * <tt>Eureka Server</tt> during shutdown
 *
 * <tt> Eureka Client </ tt>负责
 * a）<t>使用<tt> Eureka Server注册</ em>实例</ tt>
 * b）<t> <em>续订</ em>租约<tt > Eureka Server </ tt>
 * c）在关机期间从<tt> Eureka Server </ tt>取消<em>租约</ em>
 *
 * <p>
 * d) <em>Querying</em> the list of services/instances registered with
 * <tt>Eureka Server</tt>
 * <p>
 *
 * <p>
 * <tt>Eureka Client</tt> needs a configured list of <tt>Eureka Server</tt>
 * {@link java.net.URL}s to talk to.These {@link java.net.URL}s are typically amazon elastic eips
 * which do not change. All of the functions defined above fail-over to other
 * {@link java.net.URL}s specified in the list in the case of failure.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim
 * @author Spencer Gibb
 *
 */
@Singleton
public class DiscoveryClient implements EurekaClient {
    private static final Logger logger = LoggerFactory.getLogger(DiscoveryClient.class);

    // Constants
    public static final String HTTP_X_DISCOVERY_ALLOW_REDIRECT = "X-Discovery-AllowRedirect";

    private static final String VALUE_DELIMITER = ",";
    private static final String COMMA_STRING = VALUE_DELIMITER;

    /**
     * @deprecated here for legacy support as the client config has moved to be an instance variable
     * @deprecated 因为客户端配置已经变成了实例变量，所以这里用于传统支持
     */
    @Deprecated
    private static EurekaClientConfig staticClientConfig;

    // Timers
    private static final String PREFIX = "DiscoveryClient_";
    private final Counter RECONCILE_HASH_CODES_MISMATCH = Monitors.newCounter(PREFIX + "ReconcileHashCodeMismatch");
    private final com.netflix.servo.monitor.Timer FETCH_REGISTRY_TIMER = Monitors
            .newTimer(PREFIX + "FetchRegistry");
    private final Counter REREGISTER_COUNTER = Monitors.newCounter(PREFIX
            + "Reregister");

    // instance variables
    /**
     * 线程池
     *
     * A scheduler to be used for the following 3 tasks: 【目前只有两个】
     * - updating service urls 更新服务urls
     * - scheduling a TimedSuperVisorTask 执行{@link com.netflix.discovery.TimedSupervisorTask}
     */
    private final ScheduledExecutorService scheduler;
    // additional executors for supervised subtasks
    /**
     * 心跳执行器
     */
    private final ThreadPoolExecutor heartbeatExecutor;
    /**
     * {@link #localRegionApps} 刷新执行器
     */
    private final ThreadPoolExecutor cacheRefreshExecutor;

    /**
     * 生成健康检查回调的工厂
     */
    private final Provider<HealthCheckHandler> healthCheckHandlerProvider;
    /**
     * 生成健康检查处理器的工厂
     */
    private final Provider<HealthCheckCallback> healthCheckCallbackProvider;
    /**
     * 向 Eureka-Server 注册之前的处理器
     */
    private final PreRegistrationHandler preRegistrationHandler;
    /**
     * Applications 在本地的缓存
     */
    private final AtomicReference<Applications> localRegionApps = new AtomicReference<Applications>();
    private final Lock fetchRegistryUpdateLock = new ReentrantLock();
    /**
     * 拉取注册信息次数
     *
     * monotonically increasing generation counter to ensure stale threads do not reset registry to an older version
     */
    private final AtomicLong fetchRegistryGeneration;
    private final ApplicationInfoManager applicationInfoManager;
    private final InstanceInfo instanceInfo;
    /**
     * 获取哪些区域( Region )集合的注册信息
     */
    private final AtomicReference<String> remoteRegionsToFetch;
    /**
     * 获取哪些区域( Region )集合的注册信息
     */
    private final AtomicReference<String[]> remoteRegionsRef;
    private final InstanceRegionChecker instanceRegionChecker;

    private final EndpointUtils.ServiceUrlRandomizer urlRandomizer;
    private final Provider<BackupRegistry> backupRegistryProvider;
    private final EurekaTransport eurekaTransport;

    private volatile HealthCheckHandler healthCheckHandler;
    private volatile Map<String, Applications> remoteRegionVsApps = new ConcurrentHashMap<>();
    private volatile InstanceInfo.InstanceStatus lastRemoteInstanceStatus = InstanceInfo.InstanceStatus.UNKNOWN;
    /**
     * Eureka 事件监听器
     */
    private final CopyOnWriteArraySet<EurekaEventListener> eventListeners = new CopyOnWriteArraySet<>();

    /**
     * 应用路径 + 对象编号 拼接
     * 无实际业务用途，用于打 logger
     */
    private String appPathIdentifier;
    /**
     * 应用实例状态变更监听器
     */
    private ApplicationInfoManager.StatusChangeListener statusChangeListener;
    /**
     * 应用实例信息复制器
     */
    private InstanceInfoReplicator instanceInfoReplicator;
    /**
     * 注册信息的应用实例数
     */
    private volatile int registrySize = 0;
    /**
     * 最后成功从 Eureka-Server 拉取注册信息时间戳
     */
    private volatile long lastSuccessfulRegistryFetchTimestamp = -1;
    /**
     * 最后成功向 Eureka-Server 心跳时间戳
     */
    private volatile long lastSuccessfulHeartbeatTimestamp = -1;
    /**
     * 心跳监控
     */
    private final ThresholdLevelsMetric heartbeatStalenessMonitor;
    /**
     * 拉取监控
     */
    private final ThresholdLevelsMetric registryStalenessMonitor;

    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    protected final EurekaClientConfig clientConfig;
    protected final EurekaTransportConfig transportConfig;

    /**
     * 初始化完成时间戳
     */
    private final long initTimestampMs;

    private static final class EurekaTransport {
        private ClosableResolver bootstrapResolver;
        private TransportClientFactory transportClientFactory;

        private EurekaHttpClient registrationClient;
        private EurekaHttpClientFactory registrationClientFactory;

        private EurekaHttpClient queryClient;
        private EurekaHttpClientFactory queryClientFactory;

        void shutdown() {
            if (registrationClientFactory != null) {
                registrationClientFactory.shutdown();
            }

            if (queryClientFactory != null) {
                queryClientFactory.shutdown();
            }

            if (registrationClient != null) {
                registrationClient.shutdown();
            }

            if (queryClient != null) {
                queryClient.shutdown();
            }

            if (transportClientFactory != null) {
                transportClientFactory.shutdown();
            }

            if (bootstrapResolver != null) {
                bootstrapResolver.shutdown();
            }
        }
    }

    public static class DiscoveryClientOptionalArgs extends Jersey1DiscoveryClientOptionalArgs {

    }

    /**
     * Assumes applicationInfoManager is already initialized
     *
     * @deprecated use constructor that takes ApplicationInfoManager instead of InstanceInfo directly
     */
    @Deprecated
    public DiscoveryClient(InstanceInfo myInfo, EurekaClientConfig config) {
        this(myInfo, config, null);
    }

    /**
     * Assumes applicationInfoManager is already initialized
     *
     * @deprecated use constructor that takes ApplicationInfoManager instead of InstanceInfo directly
     */
    @Deprecated
    public DiscoveryClient(InstanceInfo myInfo, EurekaClientConfig config, DiscoveryClientOptionalArgs args) {
        this(ApplicationInfoManager.getInstance(), config, args);
    }

    /**
     * @deprecated use constructor that takes ApplicationInfoManager instead of InstanceInfo directly
     */
    @Deprecated
    public DiscoveryClient(InstanceInfo myInfo, EurekaClientConfig config, AbstractDiscoveryClientOptionalArgs args) {
        this(ApplicationInfoManager.getInstance(), config, args);
    }

    public DiscoveryClient(ApplicationInfoManager applicationInfoManager, EurekaClientConfig config) {
        this(applicationInfoManager, config, null);
    }

    /**
     * @deprecated use the version that take {@link com.netflix.discovery.AbstractDiscoveryClientOptionalArgs} instead
     */
    @Deprecated
    public DiscoveryClient(ApplicationInfoManager applicationInfoManager, final EurekaClientConfig config, DiscoveryClientOptionalArgs args) {
        this(applicationInfoManager, config, (AbstractDiscoveryClientOptionalArgs) args);
    }

    public DiscoveryClient(ApplicationInfoManager applicationInfoManager, final EurekaClientConfig config, AbstractDiscoveryClientOptionalArgs args) {
        // 关键逻辑
        this(applicationInfoManager, config, args, new Provider<BackupRegistry>() {
            private volatile BackupRegistry backupRegistryInstance;

            @Override
            public synchronized BackupRegistry get() {
                if (backupRegistryInstance == null) {
                    String backupRegistryClassName = config.getBackupRegistryImpl();
                    if (null != backupRegistryClassName) {
                        try {
                            backupRegistryInstance = (BackupRegistry) Class.forName(backupRegistryClassName).newInstance();
                            logger.info("Enabled backup registry of type " + backupRegistryInstance.getClass());
                        } catch (InstantiationException e) {
                            logger.error("Error instantiating BackupRegistry.", e);
                        } catch (IllegalAccessException e) {
                            logger.error("Error instantiating BackupRegistry.", e);
                        } catch (ClassNotFoundException e) {
                            logger.error("Error instantiating BackupRegistry.", e);
                        }
                    }

                    if (backupRegistryInstance == null) {
                        logger.warn("Using default backup registry implementation which does not do anything.");
                        backupRegistryInstance = new NotImplementedRegistryImpl();
                    }
                }

                return backupRegistryInstance;
            }
        });
    }

    @Inject
    DiscoveryClient(ApplicationInfoManager applicationInfoManager, EurekaClientConfig config, AbstractDiscoveryClientOptionalArgs args,
                    Provider<BackupRegistry> backupRegistryProvider) {
        // 赋值 AbstractDiscoveryClientOptionalArgs
        // 刚启动初始化的时候，args为空
        if (args != null) {
            this.healthCheckHandlerProvider = args.healthCheckHandlerProvider;
            this.healthCheckCallbackProvider = args.healthCheckCallbackProvider;
            this.eventListeners.addAll(args.getEventListeners());
            this.preRegistrationHandler = args.preRegistrationHandler;
        } else {
            this.healthCheckCallbackProvider = null;
            this.healthCheckHandlerProvider = null;
            this.preRegistrationHandler = null;
        }

        // 赋值 ApplicationInfoManager、EurekaClientConfig
        this.applicationInfoManager = applicationInfoManager;
        // 获取此eureka实例信息
        InstanceInfo myInfo = applicationInfoManager.getInfo();

        clientConfig = config;
        staticClientConfig = clientConfig;
        transportConfig = config.getTransportConfig();
        instanceInfo = myInfo;
        if (myInfo != null) {
            appPathIdentifier = instanceInfo.getAppName() + "/" + instanceInfo.getId(); // 无实际业务用途，用于打 logger
        } else {
            logger.warn("Setting instanceInfo to a passed in null value");
        }

        // 赋值 BackupRegistry
        this.backupRegistryProvider = backupRegistryProvider;

        // 初始化 InstanceInfoBasedUrlRandomizer TODO 什么用途
        this.urlRandomizer = new EndpointUtils.InstanceInfoBasedUrlRandomizer(instanceInfo);

        // 初始化应用集合在本地的缓存
        localRegionApps.set(new Applications());

        // 赋值拉取注册信息次数为0
        fetchRegistryGeneration = new AtomicLong(0);

        // 获取哪些 Region 集合的注册信息
        remoteRegionsToFetch = new AtomicReference<>(clientConfig.fetchRegistryForRemoteRegions());
        remoteRegionsRef = new AtomicReference<>(remoteRegionsToFetch.get() == null ? null : remoteRegionsToFetch.get().split(","));

        // 初始化拉取、心跳的监控
        // 各个检测的阈值
        if (config.shouldFetchRegistry()) {
            // --------------------- 关键方法------------------
            this.registryStalenessMonitor = new ThresholdLevelsMetric(this, METRIC_REGISTRY_PREFIX + "lastUpdateSec_", new long[]{15L, 30L, 60L, 120L, 240L, 480L});
        } else {
            this.registryStalenessMonitor = ThresholdLevelsMetric.NO_OP_METRIC;
        }

        // 指示此实例是否应将其信息注册到eureka服务器以供其他人发现。
        // 在某些情况下，您不希望发现您的实例，而您只是想要发现其他实例。
        if (config.shouldRegisterWithEureka()) {
            // 注册各个阈值
            this.heartbeatStalenessMonitor = new ThresholdLevelsMetric(this, METRIC_REGISTRATION_PREFIX + "lastHeartbeatSec_", new long[]{15L, 30L, 60L, 120L, 240L, 480L});
        } else {
            // 不操作阈值信息
            this.heartbeatStalenessMonitor = ThresholdLevelsMetric.NO_OP_METRIC;
        }

        logger.info("Initializing Eureka in region {}", clientConfig.getRegion());

        // 结束初始化，当无需和 Eureka-Server 交互
        if (!config.shouldRegisterWithEureka() && !config.shouldFetchRegistry()) {
            logger.info("Client configured to neither register nor query for data.");
            scheduler = null;
            heartbeatExecutor = null;
            cacheRefreshExecutor = null;
            eurekaTransport = null;
            instanceRegionChecker = new InstanceRegionChecker(new PropertyBasedAzToRegionMapper(config), clientConfig.getRegion());

            // This is a bit of hack to allow for existing code using DiscoveryManager.getInstance()
            // to work with DI'd DiscoveryClient
            DiscoveryManager.getInstance().setDiscoveryClient(this);
            DiscoveryManager.getInstance().setEurekaClientConfig(config);

            initTimestampMs = System.currentTimeMillis();
            logger.info("Discovery Client initialized at timestamp {} with initial instances count: {}",
                    initTimestampMs, this.getApplications().size());

            return;  // no need to setup up an network tasks and we are done
        }

        try {
            // 初始化线程池
            // default size of 2 - 1 each for heartbeat and cacheRefresh
            scheduler = Executors.newScheduledThreadPool(2,
                    new ThreadFactoryBuilder()
                            .setNameFormat("DiscoveryClient-%d")
                            .setDaemon(true)
                            .build());

            // 心跳线程池
            // core为1，max默认为5
            heartbeatExecutor = new ThreadPoolExecutor(
                    1, clientConfig.getHeartbeatExecutorThreadPoolSize(), 0, TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(),
                    new ThreadFactoryBuilder()
                            .setNameFormat("DiscoveryClient-HeartbeatExecutor-%d")
                            .setDaemon(true)
                            .build()
            );  // use direct handoff

            // 缓存刷新线程池
            // core为1，max默认为10
            cacheRefreshExecutor = new ThreadPoolExecutor(
                    1, clientConfig.getCacheRefreshExecutorThreadPoolSize(), 0, TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(),
                    new ThreadFactoryBuilder()
                            .setNameFormat("DiscoveryClient-CacheRefreshExecutor-%d")
                            .setDaemon(true)
                            .build()
            );  // use direct handoff

            // 初始化 Eureka 网络通信相关
            eurekaTransport = new EurekaTransport();
            // ------------------------关键方法----------------------
            // 服务器端点定时任务
            scheduleServerEndpointTask(eurekaTransport, args);

            // 初始化 InstanceRegionChecker
            AzToRegionMapper azToRegionMapper;
            // 是否使用 DNS 方式获取 Eureka-Server URL 地址
            if (clientConfig.shouldUseDnsForFetchingServiceUrls()) {
                // 使用dns
                azToRegionMapper = new DNSBasedAzToRegionMapper(clientConfig);
            } else {
                // 不使用dns
                azToRegionMapper = new PropertyBasedAzToRegionMapper(clientConfig);
            }
            if (null != remoteRegionsToFetch.get()) {
                azToRegionMapper.setRegionsToFetch(remoteRegionsToFetch.get().split(","));
            }
            instanceRegionChecker = new InstanceRegionChecker(azToRegionMapper, clientConfig.getRegion());
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize DiscoveryClient!", e);
        }

        // --------------------关键方法--------------------
        // fetchRegistry()是否获取注册表信息
        if (clientConfig.shouldFetchRegistry() && !fetchRegistry(false)) {
            // -----------------------关键方法---------------------
            // 如果所有eureka服务器URL都无法访问，请从备份注册表中获取注册表信息。
            fetchRegistryFromBackup();
        }

        // 执行向 Eureka-Server 注册之前的处理器
        // call and execute the pre registration handler before all background tasks (inc registration) is started
        if (this.preRegistrationHandler != null) {
            this.preRegistrationHandler.beforeRegistration();
        }

        // ---------------------------关键方法---------------------
        // 初始化定时任务
        initScheduledTasks();

        // 向 Servo 注册监控
        try {
            Monitors.registerObject(this);
        } catch (Throwable e) {
            logger.warn("Cannot register timers", e);
        }

        // 初始化完成
        // This is a bit of hack to allow for existing code using DiscoveryManager.getInstance()
        // to work with DI'd DiscoveryClient
        DiscoveryManager.getInstance().setDiscoveryClient(this);
        DiscoveryManager.getInstance().setEurekaClientConfig(config);

        initTimestampMs = System.currentTimeMillis();
        logger.info("Discovery Client initialized at timestamp {} with initial instances count: {}",
                initTimestampMs, this.getApplications().size());
    }

    @Override
    public EurekaClientConfig getEurekaClientConfig() {
        return clientConfig;
    }

    private void scheduleServerEndpointTask(EurekaTransport eurekaTransport,
                                            AbstractDiscoveryClientOptionalArgs args) {


        Collection<?> additionalFilters = args == null
                ? Collections.emptyList()
                : args.additionalFilters;

        EurekaJerseyClient providedJerseyClient = args == null
                ? null
                : args.eurekaJerseyClient;

        TransportClientFactories argsTransportClientFactories = null;
        if (args != null && args.getTransportClientFactories() != null) {
            argsTransportClientFactories = args.getTransportClientFactories();
        }

        // Ignore the raw types warnings since the client filter interface changed between jersey 1/2
        // 忽略原始类型警告，因为客户端过滤器接口在jersey 1/2之间更改
        @SuppressWarnings("rawtypes")
        TransportClientFactories transportClientFactories = argsTransportClientFactories == null
                ? new Jersey1TransportClientFactories()
                : argsTransportClientFactories;

        // If the transport factory was not supplied with args, assume they are using jersey 1 for passivity
        // noinspection unchecked
        // 如果transport工厂没有提供参数，假设他们使用jersey 1 进行未被检查的被动检查
        eurekaTransport.transportClientFactory = providedJerseyClient == null
                ? transportClientFactories.newTransportClientFactory(clientConfig, additionalFilters, applicationInfoManager.getInfo())
                : transportClientFactories.newTransportClientFactory(additionalFilters, providedJerseyClient);

        // 初始化 应用解析器的应用实例数据源 TODO[0028]写入集群和读取集群
        ApplicationsResolver.ApplicationsSource applicationsSource = new ApplicationsResolver.ApplicationsSource() {
            @Override
            public Applications getApplications(int stalenessThreshold, TimeUnit timeUnit) {
                long thresholdInMs = TimeUnit.MILLISECONDS.convert(stalenessThreshold, timeUnit);
                long delay = getLastSuccessfulRegistryFetchTimePeriod();
                if (delay > thresholdInMs) {
                    logger.info("Local registry is too stale for local lookup. Threshold:{}, actual:{}",
                            thresholdInMs, delay);
                    return null;
                } else {
                    return localRegionApps.get();
                }
            }
        };

        // -------------------------关键方法-------------------------
        // 创建 EndPoint 解析器
        eurekaTransport.bootstrapResolver = EurekaHttpClients.newBootstrapResolver(
                clientConfig,
                transportConfig,
                eurekaTransport.transportClientFactory,
                applicationInfoManager.getInfo(),
                applicationsSource
        );

        if (clientConfig.shouldRegisterWithEureka()) {
            // 需要此实例是否应将其信息注册到eureka服务器以供其他人发现。
            EurekaHttpClientFactory newRegistrationClientFactory = null;
            EurekaHttpClient newRegistrationClient = null;
            try {
                // --------------------关键方法------------------
                // 创建可用于连接不同eureka服务器的client
                newRegistrationClientFactory = EurekaHttpClients.registrationClientFactory(
                        eurekaTransport.bootstrapResolver,
                        eurekaTransport.transportClientFactory,
                        transportConfig
                );
                newRegistrationClient = newRegistrationClientFactory.newClient();
            } catch (Exception e) {
                logger.warn("Transport initialization failure", e);
            }
            eurekaTransport.registrationClientFactory = newRegistrationClientFactory;
            eurekaTransport.registrationClient = newRegistrationClient;
        }

        // new method (resolve from primary servers for read)
        // Configure new transport layer (candidate for injecting in the future)
        // 新方法（从主服务器解析以进行读取）
        // 配置新的传输层（将来注入的候选者）
        if (clientConfig.shouldFetchRegistry()) {
            // 需要此客户端是否应从eureka服务器获取eureka注册表信息。
            EurekaHttpClientFactory newQueryClientFactory = null;
            EurekaHttpClient newQueryClient = null;
            try {
                // --------------------关键方法--------------------
                // 创建查询客户端工厂
                newQueryClientFactory = EurekaHttpClients.queryClientFactory(
                        eurekaTransport.bootstrapResolver,
                        eurekaTransport.transportClientFactory,
                        clientConfig,
                        transportConfig,
                        applicationInfoManager.getInfo(),
                        applicationsSource
                );
                newQueryClient = newQueryClientFactory.newClient();
            } catch (Exception e) {
                logger.warn("Transport initialization failure", e);
            }
            eurekaTransport.queryClientFactory = newQueryClientFactory;
            eurekaTransport.queryClient = newQueryClient;
        }
    }

    @Override
    public ApplicationInfoManager getApplicationInfoManager() {
        return applicationInfoManager;
    }

    /*
     * (non-Javadoc)
     * @see com.netflix.discovery.shared.LookupService#getApplication(java.lang.String)
     */
    @Override
    public Application getApplication(String appName) {
        return getApplications().getRegisteredApplications(appName);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.discovery.shared.LookupService#getApplications()
     */
    @Override
    public Applications getApplications() {
        return localRegionApps.get();
    }

    @Override
    public Applications getApplicationsForARegion(@Nullable String region) {
        if (instanceRegionChecker.isLocalRegion(region)) {
            return localRegionApps.get();
        } else {
            return remoteRegionVsApps.get(region);
        }
    }

    public Set<String> getAllKnownRegions() {
        String localRegion = instanceRegionChecker.getLocalRegion();
        if (!remoteRegionVsApps.isEmpty()) {
            Set<String> regions = remoteRegionVsApps.keySet();
            Set<String> toReturn = new HashSet<String>(regions);
            toReturn.add(localRegion);
            return toReturn;
        } else {
            return Collections.singleton(localRegion);
        }
    }

    /*
     * (non-Javadoc)
     * @see com.netflix.discovery.shared.LookupService#getInstancesById(java.lang.String)
     */
    @Override
    public List<InstanceInfo> getInstancesById(String id) {
        List<InstanceInfo> instancesList = new ArrayList<InstanceInfo>();
        for (Application app : this.getApplications()
                .getRegisteredApplications()) {
            InstanceInfo instanceInfo = app.getByInstanceId(id);
            if (instanceInfo != null) {
                instancesList.add(instanceInfo);
            }
        }
        return instancesList;
    }

    /**
     * Register {@link HealthCheckCallback} with the eureka client.
     *
     * Once registered, the eureka client will invoke the
     * {@link HealthCheckCallback} in intervals specified by
     * {@link EurekaClientConfig#getInstanceInfoReplicationIntervalSeconds()}.
     *
     * @param callback app specific healthcheck.
     *
     * @deprecated Use
     */
    @Deprecated
    @Override
    public void registerHealthCheckCallback(HealthCheckCallback callback) {
        if (instanceInfo == null) {
            logger.error("Cannot register a listener for instance info since it is null!");
        }
        if (callback != null) {
            healthCheckHandler = new HealthCheckCallbackToHandlerBridge(callback);
        }
    }

    @Override
    public void registerHealthCheck(HealthCheckHandler healthCheckHandler) {
        if (instanceInfo == null) {
            logger.error("Cannot register a healthcheck handler when instance info is null!");
        }
        if (healthCheckHandler != null) {
            this.healthCheckHandler = healthCheckHandler;
            // schedule an onDemand update of the instanceInfo when a new healthcheck handler is registered
            if (instanceInfoReplicator != null) {
                instanceInfoReplicator.onDemandUpdate();
            }
        }
    }

    @Override
    public void registerEventListener(EurekaEventListener eventListener) {
        this.eventListeners.add(eventListener);
    }

    @Override
    public boolean unregisterEventListener(EurekaEventListener eventListener) {
        return this.eventListeners.remove(eventListener);
    }

    /**
     * Gets the list of instances matching the given VIP Address.
     *
     * @param vipAddress
     *            - The VIP address to match the instances for.
     * @param secure
     *            - true if it is a secure vip address, false otherwise
     * @return - The list of {@link InstanceInfo} objects matching the criteria
     */
    @Override
    public List<InstanceInfo> getInstancesByVipAddress(String vipAddress, boolean secure) {
        return getInstancesByVipAddress(vipAddress, secure, instanceRegionChecker.getLocalRegion());
    }

    /**
     * Gets the list of instances matching the given VIP Address in the passed region.
     *
     * @param vipAddress - The VIP address to match the instances for.
     * @param secure - true if it is a secure vip address, false otherwise
     * @param region - region from which the instances are to be fetched. If <code>null</code> then local region is
     *               assumed.
     *
     * @return - The list of {@link InstanceInfo} objects matching the criteria, empty list if not instances found.
     */
    @Override
    public List<InstanceInfo> getInstancesByVipAddress(String vipAddress, boolean secure,
                                                       @Nullable String region) {
        if (vipAddress == null) {
            throw new IllegalArgumentException(
                    "Supplied VIP Address cannot be null");
        }
        Applications applications;
        if (instanceRegionChecker.isLocalRegion(region)) {
            applications = this.localRegionApps.get();
        } else {
            applications = remoteRegionVsApps.get(region);
            if (null == applications) {
                logger.debug("No applications are defined for region {}, so returning an empty instance list for vip "
                        + "address {}.", region, vipAddress);
                return Collections.emptyList();
            }
        }

        if (!secure) {
            return applications.getInstancesByVirtualHostName(vipAddress);
        } else {
            return applications.getInstancesBySecureVirtualHostName(vipAddress);

        }

    }

    /**
     * Gets the list of instances matching the given VIP Address and the given
     * application name if both of them are not null. If one of them is null,
     * then that criterion is completely ignored for matching instances.
     *
     * @param vipAddress
     *            - The VIP address to match the instances for.
     * @param appName
     *            - The applicationName to match the instances for.
     * @param secure
     *            - true if it is a secure vip address, false otherwise.
     * @return - The list of {@link InstanceInfo} objects matching the criteria.
     */
    @Override
    public List<InstanceInfo> getInstancesByVipAddressAndAppName(
            String vipAddress, String appName, boolean secure) {

        List<InstanceInfo> result = new ArrayList<InstanceInfo>();
        if (vipAddress == null && appName == null) {
            throw new IllegalArgumentException(
                    "Supplied VIP Address and application name cannot both be null");
        } else if (vipAddress != null && appName == null) {
            return getInstancesByVipAddress(vipAddress, secure);
        } else if (vipAddress == null && appName != null) {
            Application application = getApplication(appName);
            if (application != null) {
                result = application.getInstances();
            }
            return result;
        }

        String instanceVipAddress;
        for (Application app : getApplications().getRegisteredApplications()) {
            for (InstanceInfo instance : app.getInstances()) {
                if (secure) {
                    instanceVipAddress = instance.getSecureVipAddress();
                } else {
                    instanceVipAddress = instance.getVIPAddress();
                }
                if (instanceVipAddress == null) {
                    continue;
                }
                String[] instanceVipAddresses = instanceVipAddress
                        .split(COMMA_STRING);

                // If the VIP Address is delimited by a comma, then consider to
                // be a list of VIP Addresses.
                // Try to match at least one in the list, if it matches then
                // return the instance info for the same
                for (String vipAddressFromList : instanceVipAddresses) {
                    if (vipAddress.equalsIgnoreCase(vipAddressFromList.trim())
                            && appName.equalsIgnoreCase(instance.getAppName())) {
                        result.add(instance);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.netflix.discovery.shared.LookupService#getNextServerFromEureka(java
     * .lang.String, boolean)
     */
    @Override
    public InstanceInfo getNextServerFromEureka(String virtualHostname, boolean secure) {
        List<InstanceInfo> instanceInfoList = this.getInstancesByVipAddress(
                virtualHostname, secure);
        if (instanceInfoList == null || instanceInfoList.isEmpty()) {
            throw new RuntimeException("No matches for the virtual host name :"
                    + virtualHostname);
        }
        Applications apps = this.localRegionApps.get();
        int index = (int) (apps.getNextIndex(virtualHostname,
                secure).incrementAndGet() % instanceInfoList.size());
        return instanceInfoList.get(index);
    }

    /**
     * Get all applications registered with a specific eureka service.
     *
     * @param serviceUrl
     *            - The string representation of the service url.
     * @return - The registry information containing all applications.
     */
    @Override
    public Applications getApplications(String serviceUrl) {
        try {
            EurekaHttpResponse<Applications> response = clientConfig.getRegistryRefreshSingleVipAddress() == null
                    ? eurekaTransport.queryClient.getApplications()
                    : eurekaTransport.queryClient.getVip(clientConfig.getRegistryRefreshSingleVipAddress());
            if (response.getStatusCode() == 200) {
                logger.debug(PREFIX + appPathIdentifier + " -  refresh status: " + response.getStatusCode());
                return response.getEntity();
            }
            logger.error(PREFIX + appPathIdentifier + " - was unable to refresh its cache! status = " + response.getStatusCode());
        } catch (Throwable th) {
            logger.error(PREFIX + appPathIdentifier + " - was unable to refresh its cache! status = " + th.getMessage(), th);
        }
        return null;
    }

    /**
     * Register with the eureka service by making the appropriate REST call.
     *
     * 通过进行适当的REST调用来注册eureka服务。
     */
    boolean register() throws Throwable {
        logger.info(PREFIX + appPathIdentifier + ": registering service...");
        EurekaHttpResponse<Void> httpResponse;
        try {
            httpResponse = eurekaTransport.registrationClient.register(instanceInfo);
        } catch (Exception e) {
            logger.warn("{} - registration failed {}", PREFIX + appPathIdentifier, e.getMessage(), e);
            throw e;
        }
        if (logger.isInfoEnabled()) {
            logger.info("{} - registration status: {}", PREFIX + appPathIdentifier, httpResponse.getStatusCode());
        }
        return httpResponse.getStatusCode() == 204;
    }

    /**
     * Renew with the eureka service by making the appropriate REST call
     *
     * 通过进行适当的REST调用，使用eureka服务进行更新
     */
    boolean renew() {
        EurekaHttpResponse<InstanceInfo> httpResponse;
        try {
            // ----------------------关键方法-------------------
            // 发起续租请求
            httpResponse = eurekaTransport.registrationClient.sendHeartBeat(instanceInfo.getAppName(), instanceInfo.getId(), instanceInfo, null);
            logger.debug("{} - Heartbeat status: {}", PREFIX + appPathIdentifier, httpResponse.getStatusCode());
            if (httpResponse.getStatusCode() == 404) {
                REREGISTER_COUNTER.increment();
                logger.info("{} - Re-registering apps/{}", PREFIX + appPathIdentifier, instanceInfo.getAppName());
                long timestamp = instanceInfo.setIsDirtyWithTime();
                // ----------------------关键方法-------------------
                // rest发起注册
                boolean success = register();
                if (success) {
                    instanceInfo.unsetIsDirty(timestamp);
                }
                return success;
            }
            return httpResponse.getStatusCode() == 200;
        } catch (Throwable e) {
            logger.error("{} - was unable to send heartbeat!", PREFIX + appPathIdentifier, e);
            return false;
        }
    }

    /**
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     *
     * Get the list of all eureka service urls from properties file for the eureka client to talk to.
     *
     * @param instanceZone The zone in which the client resides
     * @param preferSameZone true if we have to prefer the same zone as the client, false otherwise
     * @return The list of all eureka service urls for the eureka client to talk to
     */
    @Deprecated
    @Override
    public List<String> getServiceUrlsFromConfig(String instanceZone, boolean preferSameZone) {
        return EndpointUtils.getServiceUrlsFromConfig(clientConfig, instanceZone, preferSameZone);
    }

    /**
     * Shuts down Eureka Client. Also sends a deregistration request to the
     * eureka server.
     *
     * 关闭Eureka客户端。 还向eureka服务器发送注销请求。
     */
    @PreDestroy
    @Override
    public synchronized void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            logger.info("Shutting down DiscoveryClient ...");

            if (statusChangeListener != null && applicationInfoManager != null) {
                applicationInfoManager.unregisterStatusChangeListener(statusChangeListener.getId());
            }

            cancelScheduledTasks();

            // If APPINFO was registered
            // 如果APPINFO已注册
            if (applicationInfoManager != null
                    && clientConfig.shouldRegisterWithEureka() // eureka.registration.enabled = true
                    && clientConfig.shouldUnregisterOnShutdown()) { // eureka.shouldUnregisterOnShutdown = true
                applicationInfoManager.setInstanceStatus(InstanceStatus.DOWN);
                // ----------------------关键方法-----------------------
                // 取消注册与eureka服务
                unregister();
            }

            if (eurekaTransport != null) {
                eurekaTransport.shutdown();
            }

            heartbeatStalenessMonitor.shutdown();
            registryStalenessMonitor.shutdown();

            logger.info("Completed shut down of DiscoveryClient");
        }
    }

    /**
     * unregister w/ the eureka service.
     */
    void unregister() {
        // It can be null if shouldRegisterWithEureka == false
        // 如果shouldRegisterWithEureka == false，则可以为null
        if(eurekaTransport != null && eurekaTransport.registrationClient != null) {
            try {
                logger.info("Unregistering ...");
                EurekaHttpResponse<Void> httpResponse = eurekaTransport.registrationClient.cancel(instanceInfo.getAppName(), instanceInfo.getId());
                logger.info(PREFIX + appPathIdentifier + " - deregister  status: " + httpResponse.getStatusCode());
            } catch (Exception e) {
                logger.error(PREFIX + appPathIdentifier + " - de-registration failed" + e.getMessage(), e);
            }
        }
    }

    /**
     * Fetches the registry information.
     *
     * 获取注册表信息。
     *
     * <p>
     * This method tries to get only deltas after the first fetch unless there
     * is an issue in reconciling eureka server and client registry information.
     *
     * 除非在协调eureka服务器和客户端注册表信息时存在问题，否则此方法尝试仅在第一次提取后获取增量。
     *
     * </p>
     *
     * @param forceFullRegistryFetch Forces a full registry fetch.
     *
     * @return true if the registry was fetched
     */
    private boolean fetchRegistry(boolean forceFullRegistryFetch) {
        Stopwatch tracer = FETCH_REGISTRY_TIMER.start();

        try {
            // 获取 本地缓存的注册的应用实例集合
            // If the delta is disabled or if it is the first time, get all
            // applications
            Applications applications = getApplications();

            if (clientConfig.shouldDisableDelta() // 禁用增量获取
                    || (!Strings.isNullOrEmpty(clientConfig.getRegistryRefreshSingleVipAddress()))
                    || forceFullRegistryFetch // 在客户端启动的时候默认是fasle
                    || (applications == null) // 空
                    || (applications.getRegisteredApplications().size() == 0) // 空
                    || (applications.getVersion() == -1)) //客户端应用程序没有最新的库支持delta
            {
                // 禁用增量获取 || 单例VIP地址为空 || false || applications == null
                //      从eureka获取所有已注册的应用程序的list.size == 0 || 客户端应用版本 == -1
                // 全量获取
                logger.info("Disable delta property : {}", clientConfig.shouldDisableDelta());
                logger.info("Single vip registry refresh property : {}", clientConfig.getRegistryRefreshSingleVipAddress());
                logger.info("Force full registry fetch : {}", forceFullRegistryFetch);
                logger.info("Application is null : {}", (applications == null));
                logger.info("Registered Applications size is zero : {}",
                        (applications.getRegisteredApplications().size() == 0));
                logger.info("Application version is -1: {}", (applications.getVersion() == -1));
                // -------------------------关键方法-----------------------
                // 执行 全量获取
                getAndStoreFullRegistry();
            } else {
                // ------------------------关键方法----------------------
                // 执行 增量获取
                getAndUpdateDelta(applications);
            }
            // 设置 应用集合 hashcode
            applications.setAppsHashCode(applications.getReconcileHashCode());
            // 打印 本地缓存的注册的应用实例数量
            logTotalInstances();
        } catch (Throwable e) {
            logger.error(PREFIX + appPathIdentifier + " - was unable to refresh its cache! status = " + e.getMessage(), e);
            return false;
        } finally {
            if (tracer != null) {
                tracer.stop();
            }
        }

        // Notify about cache refresh before updating the instance remote status
        // 在更新实例远程状态之前通知缓存刷新
        // --------------------关键方法-----------------------
        // 加入缓存刷新事件
        onCacheRefreshed();

        // Update remote status based on refreshed data held in the cache
        // 根据缓存中保存的刷新数据更新远程状态
        // --------------------关键方法-----------------------
        // 更新实例远程状态
        updateInstanceRemoteStatus();

        // registry was fetched successfully, so return true
        // 注册表已成功获取，因此返回true
        return true;
    }

    private synchronized void updateInstanceRemoteStatus() {
        // Determine this instance's status for this app and set to UNKNOWN if not found
        // 确定此应用的此实例的状态，如果未找到则设置为UNKNOWN
        InstanceInfo.InstanceStatus currentRemoteInstanceStatus = null;
        if (instanceInfo.getAppName() != null) {
            Application app = getApplication(instanceInfo.getAppName());
            if (app != null) {
                // 从注册表中获得实例
                InstanceInfo remoteInstanceInfo = app.getByInstanceId(instanceInfo.getId());
                if (remoteInstanceInfo != null) {
                    // 获得当前远程实例状态
                    currentRemoteInstanceStatus = remoteInstanceInfo.getStatus();
                }
            }
        }
        if (currentRemoteInstanceStatus == null) {
            currentRemoteInstanceStatus = InstanceInfo.InstanceStatus.UNKNOWN;
        }

        // Notify if status changed
        // 通知状态是否已更改
        if (lastRemoteInstanceStatus != currentRemoteInstanceStatus) {
            // 如果远程实例最终状态 != 当前远程实例状态
            onRemoteStatusChanged(lastRemoteInstanceStatus, currentRemoteInstanceStatus);
            // 设置
            lastRemoteInstanceStatus = currentRemoteInstanceStatus;
        }
    }

    /**
     * @return Return he current instance status as seen on the Eureka server.
     */
    @Override
    public InstanceInfo.InstanceStatus getInstanceRemoteStatus() {
        return lastRemoteInstanceStatus;
    }

    private String getReconcileHashCode(Applications applications) {
        TreeMap<String, AtomicInteger> instanceCountMap = new TreeMap<String, AtomicInteger>();
        if (isFetchingRemoteRegionRegistries()) {
            for (Applications remoteApp : remoteRegionVsApps.values()) {
                remoteApp.populateInstanceCountMap(instanceCountMap);
            }
        }
        applications.populateInstanceCountMap(instanceCountMap);
        return Applications.getReconcileHashCode(instanceCountMap);
    }

    /**
     * Gets the full registry information from the eureka server and stores it locally.
     * When applying the full registry, the following flow is observed:
     *
     * if (update generation have not advanced (due to another thread))
     *   atomically set the registry to the new registry
     * fi
     *
     *
     * 从eureka服务器获取完整的注册表信息并将其存储在本地。 应用完整注册表时，会观察到以下流程：
     *
     * if（更新生成没有提前（由于另一个线程））
     *      原子地将注册表设置为新的注册表
     * fi
     *
     * @return the full registry information.
     * @throws Throwable
     *             on error.
     */
    private void getAndStoreFullRegistry() throws Throwable {
        // 拉取次数
        long currentUpdateGeneration = fetchRegistryGeneration.get();

        // 从eureka服务器获取所有实例注册表信息
        logger.info("Getting all instance registry info from the eureka server");

        // 全量获取注册信息
        Applications apps = null;
        // ---------------------关键方法---------------------
        // 获取所有的applicaitons
        EurekaHttpResponse<Applications> httpResponse = clientConfig.getRegistryRefreshSingleVipAddress() == null
                ? eurekaTransport.queryClient.getApplications(remoteRegionsRef.get())
                : eurekaTransport.queryClient.getVip(clientConfig.getRegistryRefreshSingleVipAddress(), remoteRegionsRef.get());
        if (httpResponse.getStatusCode() == Status.OK.getStatusCode()) {
            apps = httpResponse.getEntity();
        }
        logger.info("The response status is {}", httpResponse.getStatusCode());

        // 设置到本地缓存
        if (apps == null) {
            // 由于某种原因，该应用程序为null。 不存储此信息
            logger.error("The application is null for some reason. Not storing this information");
        } else if (fetchRegistryGeneration.compareAndSet(currentUpdateGeneration, currentUpdateGeneration + 1)) {
            // 防止有多个客户端同时更新程序
            // -------------------关键方法----------------
            // 过滤，只获取状态为开启( Up )的应用实例集合
            localRegionApps.set(this.filterAndShuffle(apps));
            logger.debug("Got full registry with apps hashcode {}", apps.getAppsHashCode());
        } else {
            // 不更新应用程序，因为另一个线程已经更新它
            logger.warn("Not updating applications as another thread is updating it already");
        }
    }

    /**
     * Get the delta registry information from the eureka server and update it locally.
     * When applying the delta, the following flow is observed:
     *
     * if (update generation have not advanced (due to another thread))
     *   atomically try to: update application with the delta and get reconcileHashCode
     *   abort entire processing otherwise
     *   do reconciliation if reconcileHashCode clash
     * fi
     *
     * @return the client response
     * @throws Throwable on error
     */
    private void getAndUpdateDelta(Applications applications) throws Throwable {
        long currentUpdateGeneration = fetchRegistryGeneration.get();

        // 增量获取注册信息
        Applications delta = null;
        EurekaHttpResponse<Applications> httpResponse = eurekaTransport.queryClient.getDelta(remoteRegionsRef.get());
        if (httpResponse.getStatusCode() == Status.OK.getStatusCode()) {
            delta = httpResponse.getEntity();
        }

        if (delta == null) {
            // 增量获取为空，全量获取
            logger.warn("The server does not allow the delta revision to be applied because it is not safe. "
                    + "Hence got the full registry.");
            getAndStoreFullRegistry();
        } else if (fetchRegistryGeneration.compareAndSet(currentUpdateGeneration, currentUpdateGeneration + 1)) {
            logger.debug("Got delta update with apps hashcode {}", delta.getAppsHashCode());
            String reconcileHashCode = "";
            if (fetchRegistryUpdateLock.tryLock()) {
                try {
                    // 将变化的应用集合和本地缓存的应用集合进行合并
                    updateDelta(delta);
                    // 计算本地的应用集合一致性哈希码
                    reconcileHashCode = getReconcileHashCode(applications);
                } finally {
                    fetchRegistryUpdateLock.unlock();
                }
            } else {
                logger.warn("Cannot acquire update lock, aborting getAndUpdateDelta");
            }
            // There is a diff in number of instances for some reason
            if (!reconcileHashCode.equals(delta.getAppsHashCode()) // 一致性哈希值不相等
                    || clientConfig.shouldLogDeltaDiff()) { //
                reconcileAndLogDifference(delta, reconcileHashCode);  // this makes a remoteCall
            }
        } else {
            logger.warn("Not updating application delta as another thread is updating it already");
            logger.debug("Ignoring delta update with apps hashcode {}, as another thread is updating it already", delta.getAppsHashCode());
        }
    }

    /**
     * Logs the total number of non-filtered instances stored locally.
     *
     * 记录本地存储的未过滤实例的总数。
     */
    private void logTotalInstances() {
        if (logger.isDebugEnabled()) {
            int totInstances = 0;
            for (Application application : getApplications().getRegisteredApplications()) {
                totInstances += application.getInstancesAsIsFromEureka().size();
            }
            logger.debug("The total number of all instances in the client now is {}", totInstances);
        }
    }

    /**
     * Reconcile the eureka server and client registry information and logs the differences if any.
     * When reconciling, the following flow is observed:
     *
     * make a remote call to the server for the full registry
     * calculate and log differences
     * if (update generation have not advanced (due to another thread))
     *   atomically set the registry to the new registry
     * fi
     *
     * @param delta
     *            the last delta registry information received from the eureka
     *            server.
     * @param reconcileHashCode
     *            the hashcode generated by the server for reconciliation.
     * @return ClientResponse the HTTP response object.
     * @throws Throwable
     *             on any error.
     */
    private void reconcileAndLogDifference(Applications delta, String reconcileHashCode) throws Throwable {
        logger.debug("The Reconcile hashcodes do not match, client : {}, server : {}. Getting the full registry",
                reconcileHashCode, delta.getAppsHashCode());

        RECONCILE_HASH_CODES_MISMATCH.increment();

        long currentUpdateGeneration = fetchRegistryGeneration.get();

        // 全量获取注册信息
        EurekaHttpResponse<Applications> httpResponse = clientConfig.getRegistryRefreshSingleVipAddress() == null
                ? eurekaTransport.queryClient.getApplications(remoteRegionsRef.get())
                : eurekaTransport.queryClient.getVip(clientConfig.getRegistryRefreshSingleVipAddress(), remoteRegionsRef.get());
        Applications serverApps = httpResponse.getEntity();

        if (serverApps == null) {
            logger.warn("Cannot fetch full registry from the server; reconciliation failure");
            return;
        }

        if (fetchRegistryGeneration.compareAndSet(currentUpdateGeneration, currentUpdateGeneration + 1)) {
            localRegionApps.set(this.filterAndShuffle(serverApps));
            getApplications().setVersion(delta.getVersion());
            logger.debug(
                    "The Reconcile hashcodes after complete sync up, client : {}, server : {}.",
                    getApplications().getReconcileHashCode(),
                    delta.getAppsHashCode());
        } else {
            logger.warn("Not setting the applications map as another thread has advanced the update generation");
        }
    }

    /**
     * Updates the delta information fetches from the eureka server into the
     * local cache.
     *
     * @param delta
     *            the delta information received from eureka server in the last
     *            poll cycle.
     */
    private void updateDelta(Applications delta) {
        int deltaCount = 0;
        for (Application app : delta.getRegisteredApplications()) { // 循环增量（变化）应用集合
            for (InstanceInfo instance : app.getInstances()) {
                Applications applications = getApplications();
                // TODO[0009]：RemoteRegionRegistry
                String instanceRegion = instanceRegionChecker.getInstanceRegion(instance);
                if (!instanceRegionChecker.isLocalRegion(instanceRegion)) {
                    Applications remoteApps = remoteRegionVsApps.get(instanceRegion);
                    if (null == remoteApps) {
                        remoteApps = new Applications();
                        remoteRegionVsApps.put(instanceRegion, remoteApps);
                    }
                    applications = remoteApps;
                }

                ++deltaCount;
                if (ActionType.ADDED.equals(instance.getActionType())) { // 添加
                    Application existingApp = applications.getRegisteredApplications(instance.getAppName());
                    if (existingApp == null) {
                        applications.addApplication(app);
                    }
                    logger.debug("Added instance {} to the existing apps in region {}", instance.getId(), instanceRegion);
                    applications.getRegisteredApplications(instance.getAppName()).addInstance(instance);
                } else if (ActionType.MODIFIED.equals(instance.getActionType())) { // 修改
                    Application existingApp = applications.getRegisteredApplications(instance.getAppName());
                    if (existingApp == null) {
                        applications.addApplication(app);
                    }
                    logger.debug("Modified instance {} to the existing apps ", instance.getId());

                    applications.getRegisteredApplications(instance.getAppName()).addInstance(instance);
                } else if (ActionType.DELETED.equals(instance.getActionType())) { // 删除
                    Application existingApp = applications.getRegisteredApplications(instance.getAppName());
                    if (existingApp == null) {
                        applications.addApplication(app);
                    }
                    logger.debug("Deleted instance {} to the existing apps ", instance.getId());
                    applications.getRegisteredApplications(instance.getAppName()).removeInstance(instance);
                }
            }
        }
        logger.debug("The total number of instances fetched by the delta processor : {}", deltaCount);

        getApplications().setVersion(delta.getVersion());
        // 过滤、打乱应用集合
        getApplications().shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());

        // TODO[0009]：RemoteRegionRegistry
        for (Applications applications : remoteRegionVsApps.values()) {
            applications.setVersion(delta.getVersion());
            applications.shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());
        }
    }

    /**
     * Initializes all scheduled tasks.
     *
     * 初始化所有计划任务。
     */
    private void initScheduledTasks() {
        // 从 Eureka-Server 拉取注册信息执行器
        if (clientConfig.shouldFetchRegistry()) {
            // registry cache refresh timer
            // 注册表缓存刷新计时器

            // 从eureka服务器获取注册表信息的频率（以秒为单位）
            int registryFetchIntervalSeconds = clientConfig.getRegistryFetchIntervalSeconds();
            // 注册信息缓存刷新执行超时后的延迟重试的时间。
            int expBackOffBound = clientConfig.getCacheRefreshExecutorExponentialBackOffBound();
            scheduler.schedule(
                    new TimedSupervisorTask(
                            "cacheRefresh",
                            scheduler,
                            cacheRefreshExecutor,
                            registryFetchIntervalSeconds,
                            TimeUnit.SECONDS,
                            expBackOffBound,
                            // ---------------------关键方法----------------run()
                            // 执行缓存刷新task
                            new CacheRefreshThread()
                    ),
                    registryFetchIntervalSeconds, TimeUnit.SECONDS);
        }

        // 向 Eureka-Server 心跳（续租）执行器
        if (clientConfig.shouldRegisterWithEureka()) {
            int renewalIntervalInSecs = instanceInfo.getLeaseInfo().getRenewalIntervalInSecs(); // 续租频率
            int expBackOffBound = clientConfig.getHeartbeatExecutorExponentialBackOffBound(); // 系数
            logger.info("Starting heartbeat executor: " + "renew interval is: " + renewalIntervalInSecs);

            // Heartbeat timer
            // 心跳计时器
            scheduler.schedule(
                    new TimedSupervisorTask(
                            "heartbeat",
                            scheduler,
                            heartbeatExecutor,
                            renewalIntervalInSecs,
                            TimeUnit.SECONDS,
                            expBackOffBound,
                            // ---------------------关键方法----------------run()
                            // 执行task
                            new HeartbeatThread()
                    ),
                    renewalIntervalInSecs, TimeUnit.SECONDS);

            // 创建 应用实例信息复制器
            // InstanceInfo replicator
            instanceInfoReplicator = new InstanceInfoReplicator(
                    this,
                    instanceInfo,
                    clientConfig.getInstanceInfoReplicationIntervalSeconds(),
                    2); // burstSize

            // 创建 应用实例状态变更监听器
            statusChangeListener = new ApplicationInfoManager.StatusChangeListener() {
                @Override
                public String getId() {
                    return "statusChangeListener";
                }

                @Override
                public void notify(StatusChangeEvent statusChangeEvent) {
                    if (InstanceStatus.DOWN == statusChangeEvent.getStatus() ||
                            InstanceStatus.DOWN == statusChangeEvent.getPreviousStatus()) {
                        // log at warn level if DOWN was involved
                        // 如果涉及DOWN，则记录警告级别
                        logger.warn("Saw local status change event {}", statusChangeEvent);
                    } else {
                        logger.info("Saw local status change event {}", statusChangeEvent);
                    }
                    // ----------------------关键方法------------------
                    // 按需更新
                    instanceInfoReplicator.onDemandUpdate();
                }
            };

            // ----------------------关键方法------------------
            // 注册 应用实例状态变更监听器
            if (clientConfig.shouldOnDemandUpdateStatusChange()) {
                applicationInfoManager.registerStatusChangeListener(statusChangeListener);
            }

            // ----------------------关键方法------------------
            // 开启 应用实例信息复制器
            instanceInfoReplicator.start(clientConfig.getInitialInstanceInfoReplicationIntervalSeconds());
        } else {
            logger.info("Not registering with Eureka server per configuration");
        }
    }

    private void cancelScheduledTasks() {
        if (instanceInfoReplicator != null) {
            instanceInfoReplicator.stop();
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        if (cacheRefreshExecutor != null) {
            cacheRefreshExecutor.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     *
     * Get the list of all eureka service urls from DNS for the eureka client to
     * talk to. The client picks up the service url from its zone and then fails over to
     * other zones randomly. If there are multiple servers in the same zone, the client once
     * again picks one randomly. This way the traffic will be distributed in the case of failures.
     *
     * @param instanceZone The zone in which the client resides.
     * @param preferSameZone true if we have to prefer the same zone as the client, false otherwise.
     * @return The list of all eureka service urls for the eureka client to talk to.
     */
    @Deprecated
    @Override
    public List<String> getServiceUrlsFromDNS(String instanceZone, boolean preferSameZone) {
        return EndpointUtils.getServiceUrlsFromDNS(clientConfig, instanceZone, preferSameZone, urlRandomizer);
    }

    /**
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     */
    @Deprecated
    @Override
    public List<String> getDiscoveryServiceUrls(String zone) {
        return EndpointUtils.getDiscoveryServiceUrls(clientConfig, zone, urlRandomizer);
    }

    /**
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     *
     * Get the list of EC2 URLs given the zone name.
     *
     * @param dnsName The dns name of the zone-specific CNAME
     * @param type CNAME or EIP that needs to be retrieved
     * @return The list of EC2 URLs associated with the dns name
     */
    @Deprecated
    public static Set<String> getEC2DiscoveryUrlsFromZone(String dnsName,
                                                          EndpointUtils.DiscoveryUrlType type) {
        return EndpointUtils.getEC2DiscoveryUrlsFromZone(dnsName, type);
    }

    /**
     * Refresh the current local instanceInfo. Note that after a valid refresh where changes are observed, the
     * isDirty flag on the instanceInfo is set to true
     *
     * 刷新当前的本地instanceInfo。 请注意，在观察到更改的有效刷新之后，instanceInfo上的isDirty标志设置为true
     */
    void refreshInstanceInfo() {
        // ------------------------关键方法------------
        // 刷新 数据中心信息，其实就是重新设置服务器，ip地址等
        applicationInfoManager.refreshDataCenterInfoIfRequired();
        // ------------------------关键方法------------
        // 刷新 租约信息
        applicationInfoManager.refreshLeaseInfoIfRequired();
        // 健康检查
        InstanceStatus status;
        try {
            status = getHealthCheckHandler().getStatus(instanceInfo.getStatus());
        } catch (Exception e) {
            logger.warn("Exception from healthcheckHandler.getStatus, setting status to DOWN", e);
            status = InstanceStatus.DOWN;
        }
        if (null != status) {
            // ------------------------关键方法------------
            // 设置实例状态
            applicationInfoManager.setInstanceStatus(status);
        }
    }

    /**
     * The heartbeat task that renews the lease in the given intervals.
     *
     * 以给定间隔更新租约的心跳任务。
     */
    private class HeartbeatThread implements Runnable {

        public void run() {
            // ----------------------------关键方法-------------------------
            // 通过进行适当的REST调用，使用eureka服务进行更新
            if (renew()) {
                lastSuccessfulHeartbeatTimestamp = System.currentTimeMillis();
            }
        }
    }

    @VisibleForTesting
    InstanceInfoReplicator getInstanceInfoReplicator() {
        return instanceInfoReplicator;
    }

    @VisibleForTesting
    InstanceInfo getInstanceInfo() {
        return instanceInfo;
    }

    @Override
    public HealthCheckHandler getHealthCheckHandler() {
        if (healthCheckHandler == null) {
            if (null != healthCheckHandlerProvider) {
                healthCheckHandler = healthCheckHandlerProvider.get();
            } else if (null != healthCheckCallbackProvider) {
                healthCheckHandler = new HealthCheckCallbackToHandlerBridge(healthCheckCallbackProvider.get());
            }

            if (null == healthCheckHandler) {
                healthCheckHandler = new HealthCheckCallbackToHandlerBridge(null);
            }
        }

        return healthCheckHandler;
    }

    /**
     * The task that fetches the registry information at specified intervals.
     * 以指定的时间间隔获取注册表信息的任务。
     *
     */
    class CacheRefreshThread implements Runnable {
        public void run() {
            refreshRegistry();
        }
    }

    @VisibleForTesting
    void refreshRegistry() {
        try {
            // 是否获取哪些区域( Region )集合的注册信息
            boolean isFetchingRemoteRegionRegistries = isFetchingRemoteRegionRegistries();

            // 远程区域修改
            boolean remoteRegionsModified = false;

            // This makes sure that a dynamic change to remote regions to fetch is honored.
            //这样可以确保对要获取的远程区域进行动态更改。
            String latestRemoteRegions = clientConfig.fetchRegistryForRemoteRegions();
            if (null != latestRemoteRegions) {
                String currentRemoteRegions = remoteRegionsToFetch.get();
                if (!latestRemoteRegions.equals(currentRemoteRegions)) {
                    // Both remoteRegionsToFetch and AzToRegionMapper.regionsToFetch need to be in sync
                    // remoteRegionsToFetch和AzToRegionMapper.regionsToFetch都需要同步
                    synchronized (instanceRegionChecker.getAzToRegionMapper()) {
                        if (remoteRegionsToFetch.compareAndSet(currentRemoteRegions, latestRemoteRegions)) {
                            String[] remoteRegions = latestRemoteRegions.split(",");
                            remoteRegionsRef.set(remoteRegions);
                            instanceRegionChecker.getAzToRegionMapper().setRegionsToFetch(remoteRegions);
                            remoteRegionsModified = true;
                        } else {
                            logger.info("Remote regions to fetch modified concurrently," +
                                    " ignoring change from {} to {}", currentRemoteRegions, latestRemoteRegions);
                        }
                    }
                } else {
                    // Just refresh mapping to reflect any DNS/Property change
                    // 只需刷新映射以反映任何DNS / Property更改
                    instanceRegionChecker.getAzToRegionMapper().refreshMapping();
                }
            }

            // ---------------------关键方法--------------------
            // 获取注册表信息
            boolean success = fetchRegistry(remoteRegionsModified);
            if (success) {
                // 设置 注册信息的应用实例数
                registrySize = localRegionApps.get().size();
                // 设置 最后获取注册信息时间
                lastSuccessfulRegistryFetchTimestamp = System.currentTimeMillis();
            }

            // 打印日志
            if (logger.isDebugEnabled()) {
                StringBuilder allAppsHashCodes = new StringBuilder();
                allAppsHashCodes.append("Local region apps hashcode: ");
                allAppsHashCodes.append(localRegionApps.get().getAppsHashCode());
                allAppsHashCodes.append(", is fetching remote regions? ");
                allAppsHashCodes.append(isFetchingRemoteRegionRegistries);
                for (Map.Entry<String, Applications> entry : remoteRegionVsApps.entrySet()) {
                    allAppsHashCodes.append(", Remote region: ");
                    allAppsHashCodes.append(entry.getKey());
                    allAppsHashCodes.append(" , apps hashcode: ");
                    allAppsHashCodes.append(entry.getValue().getAppsHashCode());
                }
                logger.debug("Completed cache refresh task for discovery. All Apps hash code is {} ",
                        allAppsHashCodes.toString());
            }
        } catch (Throwable e) {
            logger.error("Cannot fetch registry from server", e);
        }
    }

    /**
     * Fetch the registry information from back up registry if all eureka server
     * urls are unreachable.
     *
     * 如果所有eureka服务器URL都无法访问，请从备份注册表中获取注册表信息。
     */
    private void fetchRegistryFromBackup() {
        try {
            @SuppressWarnings("deprecation")
            BackupRegistry backupRegistryInstance = newBackupRegistryInstance();
            if (null == backupRegistryInstance) {
                // backward compatibility with the old protected method, in case it is being used.
                // 如果正在使用，则向后兼容旧的受保护方法。
                backupRegistryInstance = backupRegistryProvider.get();
            }
            if (null != backupRegistryInstance) {
                Applications apps = null;
                if (isFetchingRemoteRegionRegistries()) {
                    String remoteRegionsStr = remoteRegionsToFetch.get();
                    if (null != remoteRegionsStr) {
                        apps = backupRegistryInstance.fetchRegistry(remoteRegionsStr.split(","));
                    }
                } else {
                    apps = backupRegistryInstance.fetchRegistry();
                }
                if (apps != null) {
                    final Applications applications = this.filterAndShuffle(apps);
                    applications.setAppsHashCode(applications.getReconcileHashCode());
                    localRegionApps.set(applications);
                    logTotalInstances();
                    logger.info("Fetched registry successfully from the backup");
                }
            } else {
                logger.warn("No backup registry instance defined & unable to find any discovery servers.");
            }
        } catch (Throwable e) {
            logger.warn("Cannot fetch applications from apps although backup registry was specified", e);
        }
    }

    /**
     * @deprecated Use injection to provide {@link BackupRegistry} implementation.
     *
     * @deprecated使用注入提供{@link BackupRegistry}实现。
     */
    @Deprecated
    @Nullable
    protected BackupRegistry newBackupRegistryInstance()
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return null;
    }

    /**
     * Gets the <em>applications</em> after filtering the applications for
     * instances with only UP states and shuffling them.
     *
     * 在过滤仅具有UP状态的实例的应用程序并对其进行混洗之后，获取<em>应用程序</ em>。
     *
     * <p>
     * The filtering depends on the option specified by the configuration
     * {@link EurekaClientConfig#shouldFilterOnlyUpInstances()}. Shuffling helps
     * in randomizing the applications list there by avoiding the same instances
     * receiving traffic during start ups.
     *
     * 过滤取决于配置{@link EurekaClientConfig＃shouldFilterOnlyUpInstances（）}指定的选项。
     * 通过避免在启动期间接收流量的相同实例，Shuffling有助于随机化应用程序列表。
     * </p>
     *
     * @param apps
     *            The applications that needs to be filtered and shuffled.
     * @return The applications after the filter and the shuffle.
     */
    private Applications filterAndShuffle(Applications apps) {
        if (apps != null) {
            if (isFetchingRemoteRegionRegistries()) {
                Map<String, Applications> remoteRegionVsApps = new ConcurrentHashMap<String, Applications>();
                apps.shuffleAndIndexInstances(remoteRegionVsApps, clientConfig, instanceRegionChecker);
                for (Applications applications : remoteRegionVsApps.values()) {
                    // 过滤，只获取状态为开启( Up )的应用实例集合
                    applications.shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());
                }
                this.remoteRegionVsApps = remoteRegionVsApps;
            } else {
                apps.shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());
            }
        }
        return apps;
    }

    /**
     * 正在获取远程区域注册表
     * @return
     */
    private boolean isFetchingRemoteRegionRegistries() {
        return null != remoteRegionsToFetch.get();
    }

    /**
     * Invoked when the remote status of this client has changed.
     * Subclasses may override this method to implement custom behavior if needed.
     *
     * @param oldStatus the previous remote {@link InstanceStatus}
     * @param newStatus the new remote {@link InstanceStatus}
     */
    protected void onRemoteStatusChanged(InstanceInfo.InstanceStatus oldStatus, InstanceInfo.InstanceStatus newStatus) {
        fireEvent(new StatusChangeEvent(oldStatus, newStatus));
    }


    /**
     * Invoked every time the local registry cache is refreshed (whether changes have
     * been detected or not).
     *
     * Subclasses may override this method to implement custom behavior if needed.
     *
     * 每次刷新本地注册表缓存时调用（是否已检测到更改）。
     * 如果需要，子类可以重写此方法以实现自定义行为。
     */
    protected void onCacheRefreshed() {
        fireEvent(new CacheRefreshedEvent());
    }

    /**
     * Send the given event on the EventBus if one is available
     *
     * @param event the event to send on the eventBus
     */
    protected void fireEvent(final EurekaEvent event) {
        for (EurekaEventListener listener : eventListeners) {
            listener.onEvent(event);
        }
    }

    /**
     * @deprecated see {@link com.netflix.appinfo.InstanceInfo#getZone(String[], com.netflix.appinfo.InstanceInfo)}
     *
     * Get the zone that a particular instance is in.
     *
     * @param myInfo
     *            - The InstanceInfo object of the instance.
     * @return - The zone in which the particular instance belongs to.
     */
    @Deprecated
    public static String getZone(InstanceInfo myInfo) {
        String[] availZones = staticClientConfig.getAvailabilityZones(staticClientConfig.getRegion());
        return InstanceInfo.getZone(availZones, myInfo);
    }

    /**
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     *
     * Get the region that this particular instance is in.
     *
     * @return - The region in which the particular instance belongs to.
     */
    @Deprecated
    public static String getRegion() {
        String region = staticClientConfig.getRegion();
        if (region == null) {
            region = "default";
        }
        region = region.trim().toLowerCase();
        return region;
    }

    /**
     * @deprecated use {@link #getServiceUrlsFromConfig(String, boolean)} instead.
     */
    @Deprecated
    public static List<String> getEurekaServiceUrlsFromConfig(String instanceZone, boolean preferSameZone) {
        return EndpointUtils.getServiceUrlsFromConfig(staticClientConfig, instanceZone, preferSameZone);
    }

    public long getLastSuccessfulHeartbeatTimePeriod() {
        return lastSuccessfulHeartbeatTimestamp < 0
                ? lastSuccessfulHeartbeatTimestamp
                : System.currentTimeMillis() - lastSuccessfulHeartbeatTimestamp;
    }

    public long getLastSuccessfulRegistryFetchTimePeriod() {
        return lastSuccessfulRegistryFetchTimestamp < 0
                ? lastSuccessfulRegistryFetchTimestamp
                : System.currentTimeMillis() - lastSuccessfulRegistryFetchTimestamp;
    }

    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRATION_PREFIX + "lastSuccessfulHeartbeatTimePeriod",
            description = "How much time has passed from last successful heartbeat", type = DataSourceType.GAUGE)
    private long getLastSuccessfulHeartbeatTimePeriodInternal() {
        long delay = getLastSuccessfulHeartbeatTimePeriod();
        heartbeatStalenessMonitor.update(computeStalenessMonitorDelay(delay));
        return delay;
    }

    // for metrics only
    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRY_PREFIX + "lastSuccessfulRegistryFetchTimePeriod",
            description = "How much time has passed from last successful local registry update", type = DataSourceType.GAUGE)
    private long getLastSuccessfulRegistryFetchTimePeriodInternal() {
        long delay = getLastSuccessfulRegistryFetchTimePeriod();
        registryStalenessMonitor.update(computeStalenessMonitorDelay(delay));
        return delay;
    }

    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRY_PREFIX + "localRegistrySize",
            description = "Count of instances in the local registry", type = DataSourceType.GAUGE)
    public int localRegistrySize() {
        return registrySize;
    }


    private long computeStalenessMonitorDelay(long delay) {
        if (delay < 0) {
            return System.currentTimeMillis() - initTimestampMs;
        } else {
            return delay;
        }
    }

}
