package com.netflix.discovery;

import com.netflix.appinfo.InstanceInfo;

/**
 * Event containing the latest instance status information.  This event
 * is sent to the {@link com.netflix.eventbus.spi.EventBus} by {@link EurekaClient) whenever
 * a status change is identified from the remote Eureka server response.
 *
 * 包含最新实例状态信息的事件。 只要从远程Eureka服务器响应中识别出状态更改，
 * 就会通过{@link EurekaClient}将此事件发送到{@link com.netflix.eventbus.spi.EventBus}。
 */
public class StatusChangeEvent extends DiscoveryEvent {
    /**
     * 实例当前状态
     */
    private final InstanceInfo.InstanceStatus current;
    /**
     * 实例前一个状态
     */
    private final InstanceInfo.InstanceStatus previous;

    public StatusChangeEvent(InstanceInfo.InstanceStatus previous, InstanceInfo.InstanceStatus current) {
        super();
        this.current = current;
        this.previous = previous;
    }

    /**
     * Return the up current when the event was generated.
     * @return true if current is up or false for ALL other current values
     */
    public boolean isUp() {
        return this.current.equals(InstanceInfo.InstanceStatus.UP);
    }

    /**
     * @return The current at the time the event is generated.
     */
    public InstanceInfo.InstanceStatus getStatus() {
        return current;
    }

    /**
     * @return Return the client status immediately before the change
     */
    public InstanceInfo.InstanceStatus getPreviousStatus() {
        return previous;
    }

    @Override
    public String toString() {
        return "StatusChangeEvent [timestamp=" + getTimestamp() + ", current=" + current + ", previous="
                + previous + "]";
    }

}
