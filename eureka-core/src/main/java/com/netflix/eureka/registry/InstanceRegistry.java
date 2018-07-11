package com.netflix.eureka.registry;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.LookupService;
import com.netflix.discovery.shared.Pair;
import com.netflix.eureka.lease.LeaseManager;

import java.util.List;
import java.util.Map;

/**
 * 应用实例注册表接口。它继承了 LookupService 、LeaseManager 接口，
 * 提供应用实例的注册与发现服务。另外，它结合实际业务场景，定义了更加丰富的接口方法。
 *
 * @author Tomasz Bak
 */
public interface InstanceRegistry extends LeaseManager<InstanceInfo>, LookupService<String> {

    /**
     * 开启与关闭相关
     * @param applicationInfoManager
     * @param count
     */
    void openForTraffic(ApplicationInfoManager applicationInfoManager, int count);

    void shutdown();

    @Deprecated
    void storeOverriddenStatusIfRequired(String id, InstanceStatus overriddenStatus);

    /**
     * 存储重写状态如果需要
     * @param appName
     * @param id
     * @param overriddenStatus
     */
    void storeOverriddenStatusIfRequired(String appName, String id, InstanceStatus overriddenStatus);

    boolean statusUpdate(String appName, String id, InstanceStatus newStatus,
                         String lastDirtyTimestamp, boolean isReplication);

    /**
     * 删除状态覆盖
     * @param appName
     * @param id
     * @param newStatus
     * @param lastDirtyTimestamp
     * @param isReplication
     * @return
     */
    boolean deleteStatusOverride(String appName, String id, InstanceStatus newStatus,
                                 String lastDirtyTimestamp, boolean isReplication);

    /**
     * 重写实例状态快照
     * @return
     */
    Map<String, InstanceStatus> overriddenInstanceStatusesSnapshot();

    /**
     * 仅从本地区域获取应用程序
     * @return
     */
    Applications getApplicationsFromLocalRegionOnly();

    /**
     * 获得排序的应用程序
     * @return
     */
    List<Application> getSortedApplications();

    /**
     * Get application information.
     *
     * 获取申请信息。
     *
     * @param appName The name of the application
     * @param includeRemoteRegion true, if we need to include applications from remote regions
     *                            as indicated by the region {@link java.net.URL} by this property
     *                            {@link com.netflix.eureka.EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the application
     */
    Application getApplication(String appName, boolean includeRemoteRegion);

    /**
     * Gets the {@link InstanceInfo} information.
     *
     * 获取{@link InstanceInfo}信息
     *
     * @param appName the application name for which the information is requested.
     * @param id the unique identifier of the instance.
     * @return the information about the instance.
     */
    InstanceInfo getInstanceByAppAndId(String appName, String id);

    /**
     * Gets the {@link InstanceInfo} information.
     *
     * @param appName the application name for which the information is requested.
     * @param id the unique identifier of the instance.
     * @param includeRemoteRegions true, if we need to include applications from remote regions
     *                             as indicated by the region {@link java.net.URL} by this property
     *                             {@link com.netflix.eureka.EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the information about the instance.
     */
    InstanceInfo getInstanceByAppAndId(String appName, String id, boolean includeRemoteRegions);

    void clearRegistry();

    void initializedResponseCache();

    ResponseCache getResponseCache();

    /**
     * 在最后一分钟获得更新的数量
     * @return
     */
    long getNumOfRenewsInLastMin();

    int getNumOfRenewsPerMinThreshold();

    /**
     * 每分钟门槛获得更新数量
     * @return
     */
    int isBelowRenewThresold();

    /**
     * 获取最近注册的N个实例。
     * @return
     */
    List<Pair<Long, String>> getLastNRegisteredInstances();

    /**
     * 获取最近取消的N个实例。
     * @return
     */
    List<Pair<Long, String>> getLastNCanceledInstances();

    /**
     * Checks whether lease expiration is enabled.
     * 检查是否启用了租约到期。
     *
     * @return true if enabled
     */
    boolean isLeaseExpirationEnabled();

    /**
     * 检查是否启用了自我保护模式。
     * @return
     */
    boolean isSelfPreservationModeEnabled();

}
