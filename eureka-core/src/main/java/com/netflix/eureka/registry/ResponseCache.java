package com.netflix.eureka.registry;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 响应缓存
 * @author David Liu
 */
public interface ResponseCache {

    void invalidate(String appName, @Nullable String vipAddress, @Nullable String secureVipAddress);

    AtomicLong getVersionDelta();

    AtomicLong getVersionDeltaWithRegions();

    /**
     * Get the cached information about applications.
     *
     * 获取有关应用程序的缓存信息。
     *
     * <p>
     * If the cached information is not available it is generated on the first
     * request. After the first request, the information is then updated
     * periodically by a background thread.
     *
     * 如果缓存的信息不可用，则会在第一个请求中生成。 在第一次请求之后，然后由后台线程定期更新信息。
     * </p>
     *
     * @param key the key for which the cached information needs to be obtained.
     * @return payload which contains information about the applications.
     */
     String get(Key key);

    /**
     * Get the compressed information about the applications.
     *
     * 获取有关应用程序的压缩信息。
     *
     * @param key the key for which the compressed cached information needs to be obtained.
     * @return compressed payload which contains information about the applications.
     */
    byte[] getGZIP(Key key);
}
