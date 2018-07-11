package com.netflix.discovery.shared.transport;

/**
 * Config class that governs configurations relevant to the transport layer
 *
 * Config类，用于管理与传输层相关的配置
 *
 * @author David Liu
 */
public interface EurekaTransportConfig {

    /**
     * @return the reconnect inverval to use for sessioned clients
     * 用于会话客户端的重新连接间隔，单位：秒
     */
    int getSessionedClientReconnectIntervalSeconds();

    /**
     * @return the percentage of the full endpoints set above which the quarantine set is cleared in the range [0, 1.0]
     * 设置的完整端点的百分比，在[0,1.0]范围内清除隔离集
     */
    double getRetryableClientQuarantineRefreshPercentage();

    /**
     * @return the max staleness threshold tolerated by the applications resolver
     * 应用程序解析程序允许的最大过期阈值
     */
    int getApplicationsResolverDataStalenessThresholdSeconds();

    /**
     * By default, the applications resolver extracts the public hostname from internal InstanceInfos for resolutions.
     * Set this to true to change this behaviour to use ip addresses instead (private ip if ip type can be determined).
     *
     * 默认情况下，应用程序解析程序从内部InstanceInfos中提取公共主机名以获取解决方案。
     * 将此设置为true可将此行为更改为使用ip地址（如果可以确定ip类型，则为私有IP）。
     *
     * @return false by default
     */
    boolean applicationsResolverUseIp();

    /**
     * @return the interval to poll for the async resolver.
     * 异步解析 EndPoint 集群频率，单位：毫秒。
     */
    int getAsyncResolverRefreshIntervalMs();

    /**
     * @return the async refresh timeout threshold in ms.
     * 异步解析器预热解析 EndPoint 集群超时时间，单位：毫秒。
     */
    int getAsyncResolverWarmUpTimeoutMs();

    /**
     * @return the max threadpool size for the async resolver's executor
     * 异步解析器线程池大小。
     */
    int getAsyncExecutorThreadPoolSize();

    /**
     * The remote vipAddress of the primary eureka cluster to register with.
     * 要注册的主eureka群集的远程vipAddress。
     *
     * @return the vipAddress for the write cluster to register with
     */
    String getWriteClusterVip();

    /**
     * The remote vipAddress of the eureka cluster (either the primaries or a readonly replica) to fetch registry
     * data from.
     *
     * eureka集群的远程vipAddress（初级或只读副本）从中获取注册表数据。
     *
     * @return the vipAddress for the readonly cluster to redirect to, if applicable (can be the same as the bootstrap)
     */
    String getReadClusterVip();

    /**
     * Can be used to specify different bootstrap resolve strategies. Current supported strategies are:
     *  - default (if no match): bootstrap from dns txt records or static config hostnames
     *  - composite: bootstrap from local registry if data is available
     *    and warm (see {@link #getApplicationsResolverDataStalenessThresholdSeconds()}, otherwise
     *    fall back to a backing default
     *
     *    可用于指定不同的引导程序解析策略。 目前支持的策略是：
     * - 默认（如果不匹配）：从dns txt记录或静态配置主机名引导
     * - composite：如果数据可用且准备好，则从本地注册表引导
     *（请参阅{@link #getApplicationsResolverDataStalenessThresholdSeconds（）}，否则回退到支持默认值
     *
     * @return null for the default strategy, by default
     */
    String getBootstrapResolverStrategy();

    /**
     * By default, the transport uses the same (bootstrap) resolver for queries.
     * 默认情况下，传输使用相同（引导程序）解析程序进行查询。
     *
     * Set this property to false to use an indirect resolver to resolve query targets
     * via {@link #getReadClusterVip()}. This indirect resolver may or may not return the same
     * targets as the bootstrap servers depending on how servers are setup.
     *
     * 将此属性设置为false以使用间接解析程序通过{@link #getReadClusterVip（）}解析查询目标。
     * 此间接解析程序可能会也可能不会返回与引导程序服务器相同的目标，具体取决于服务器的设置方式。
     *
     * @return true by default.
     */
    boolean useBootstrapResolverForQuery();
}
