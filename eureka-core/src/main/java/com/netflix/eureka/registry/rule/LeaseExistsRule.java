package com.netflix.eureka.registry.rule;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.eureka.lease.Lease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This rule matches if we have an existing lease for the instance that is UP or OUT_OF_SERVICE.
 *
 * 如果我们有UP或OUT_OF_SERVICE实例的现有租约，则此规则匹配。
 *
 * Created by Nikos Michalakis on 7/13/16.
 */
public class LeaseExistsRule implements InstanceStatusOverrideRule {

    private static final Logger logger = LoggerFactory.getLogger(LeaseExistsRule.class);

    @Override
    public StatusOverrideResult apply(InstanceInfo instanceInfo,
                                      Lease<InstanceInfo> existingLease,
                                      boolean isReplication) {
        // This is for backward compatibility until all applications have ASG
        // names, otherwise while starting up
        // the client status may override status replicated from other servers
        // 这是为了向后兼容，直到所有应用程序都有ASG
        // 名称，否则在启动时
        // 客户端状态可能会覆盖从其他服务器复制的状态
        if (!isReplication) {
            // 不是Eureka-Server 集群请求
            InstanceInfo.InstanceStatus existingStatus = null;
            if (existingLease != null) {
                // 获得存在的租约状态
                existingStatus = existingLease.getHolder().getStatus();
            }
            // Allow server to have its way when the status is UP or OUT_OF_SERVICE
            // 当状态为UP或OUT_OF_SERVICE时，允许服务器使用它
            if ((existingStatus != null)
                    && (InstanceInfo.InstanceStatus.OUT_OF_SERVICE.equals(existingStatus)
                    || InstanceInfo.InstanceStatus.UP.equals(existingStatus))) {
                // 租约状态不为空 && (状态等于OUT_OF_SERVICE || 状态等于等于UP) ，设置为匹配
                logger.debug("There is already an existing lease with status {}  for instance {}",
                        existingLease.getHolder().getStatus().name(),
                        existingLease.getHolder().getId());
                return StatusOverrideResult.matchingStatus(existingLease.getHolder().getStatus());
            }
        }
        // 其余状态设置为不匹配
        return StatusOverrideResult.NO_MATCH;
    }

    @Override
    public String toString() {
        return LeaseExistsRule.class.getName();
    }
}
