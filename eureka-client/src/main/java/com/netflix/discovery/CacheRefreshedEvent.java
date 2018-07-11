package com.netflix.discovery;

/**
 * This event is sent by {@link EurekaClient) whenever it has refreshed its local 
 * local cache with information received from the Eureka server.
 *
 * 此事件由{@link EurekaClient}发送，只要它使用从Eureka服务器收到的信息刷新其本地本地缓存。
 * 
 * @author brenuart
 */
public class CacheRefreshedEvent extends DiscoveryEvent {
    @Override
    public String toString() {
        return "CacheRefreshedEvent[timestamp=" + getTimestamp() + "]";
    }
}
