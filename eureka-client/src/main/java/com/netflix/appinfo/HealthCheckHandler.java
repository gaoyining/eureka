package com.netflix.appinfo;

/**
 * This provides a more granular healthcheck contract than the existing {@link HealthCheckCallback}
 *
 * 这提供了比现有更精细的健康检查合同
 *
 * @author Nitesh Kant
 */
public interface HealthCheckHandler {

    InstanceInfo.InstanceStatus getStatus(InstanceInfo.InstanceStatus currentStatus);

}
