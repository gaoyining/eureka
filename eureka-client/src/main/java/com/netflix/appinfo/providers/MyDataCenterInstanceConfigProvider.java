package com.netflix.appinfo.providers;

import javax.inject.Provider;

import com.google.inject.Inject;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.discovery.DiscoveryManager;
import com.netflix.discovery.EurekaNamespace;

public class MyDataCenterInstanceConfigProvider implements Provider<EurekaInstanceConfig> {
    @Inject(optional = true)
    @EurekaNamespace
    private String namespace;

    private MyDataCenterInstanceConfig config;

    @Override
    public synchronized MyDataCenterInstanceConfig get() {
        if (config == null) {
            if (namespace == null) {
                config = new MyDataCenterInstanceConfig();
            } else {
                config = new MyDataCenterInstanceConfig(namespace);
            }

            // TODO: Remove this when DiscoveryManager is finally no longer used
            // TODO: 当发现管理器终于不再使用时删除它
            DiscoveryManager.getInstance().setEurekaInstanceConfig(config);
        }
        return config;
    }
}
