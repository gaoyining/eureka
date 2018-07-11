package com.netflix.discovery;

import com.netflix.appinfo.ApplicationInfoManager;

/**
 * A handler that can be registered with an {@link EurekaClient} at creation time to execute
 * pre registration logic. The pre registration logic need to be synchronous to be guaranteed
 * to execute before registration.
 *
 * 可以在创建时使用{@link EurekaClient}注册以执行预注册逻辑的处理程序。 预注册逻辑需要保持同步以保证在注册之前执行。
 */
public interface PreRegistrationHandler {
    void beforeRegistration();
}
