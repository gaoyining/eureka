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
package com.netflix.appinfo;

import java.util.Map;

import com.google.inject.ImplementedBy;

/**
 * Configuration information required by the instance to register with Eureka
 * server. Once registered, users can look up information from
 * {@link com.netflix.discovery.EurekaClient} based on virtual hostname (also called VIPAddress),
 * the most common way of doing it or by other means to get the information
 * necessary to talk to other instances registered with <em>Eureka</em>.
 *
 * 实例向Eureka服务器注册所需的配置信息。 注册后，用户可以从中查找信息
 * {@link com.netflix.discovery.EurekaClient}基于虚拟主机名（也称为VIPAddress），
 * 最常用的方式或通过其他方式获取与<em> Eureka</em>注册的其他实例交谈所需的信息
 *
 * <P>
 * As requirements of registration, an id and an appname must be supplied. The id should be
 * unique within the scope of the appname.
 *
 * 作为注册要求，必须提供id和appname。 id应该在appname的范围内是唯一的。
 * </P>
 *
 * <p>
 * Note that all configurations are not effective at runtime unless and
 * otherwise specified.
 *
 * 请注意，除非另有说明，否则所有配置在运行时均无效。
 * </p>
 *
 * Eureka 应用实例配置接口
 * @author Karthik Ranganathan
 *
 */
@ImplementedBy(CloudInstanceConfig.class)
public interface EurekaInstanceConfig {

    /**
     * Get the unique Id (within the scope of the appName) of this instance to be registered with eureka.
     *
     * 对象编号。
     * 需要保证在相同应用名下唯一。
     *
     * @return the (appname scoped) unique id for this instance
     */
    String getInstanceId();

    /**
     * Get the name of the application to be registered with eureka.
     * 应用名
     *
     * @return string denoting the name.
     */
    String getAppname();

    /**
     * Get the name of the application group to be registered with eureka.
     * 应用分组
     *
     * @return string denoting the name.
     */
    String getAppGroupName();

    /**
     * Indicates whether the instance should be enabled for taking traffic as
     * soon as it is registered with eureka. Sometimes the application might
     * need to do some pre-processing before it is ready to take traffic.
     *
     * :( public API typos are the worst. I think this was meant to be "OnInit".
     *
     * 指示是否应该启用实例以便在通过eureka注册后立即获取流量。 有时应用程序可能需要在准备好接收流量之前进行一些预处理。
     * :(公共API拼写错误是最糟糕的。我认为这应该是“OnInit”。
     *
     *
     * @return true to immediately start taking traffic, false otherwise.
     */
    boolean isInstanceEnabledOnit();

    /**
     * Get the <code>non-secure</code> port on which the instance should receive
     * traffic.
     * 应用 http 端口
     *
     * @return the non-secure port on which the instance should receive traffic.
     */
    int getNonSecurePort();

    /**
     * Get the <code>Secure port</code> on which the instance should receive
     * traffic.
     * 应用 https 端口
     *
     * @return the secure port on which the instance should receive traffic.
     */
    int getSecurePort();

    /**
     * Indicates whether the <code>non-secure</code> port should be enabled for
     * traffic or not.
     *
     * 应用 http 端口是否开启
     *
     * @return true if the <code>non-secure</code> port is enabled, false
     *         otherwise.
     */
    boolean isNonSecurePortEnabled();

    /**
     * Indicates whether the <code>secure</code> port should be enabled for
     * traffic or not.
     * 应用 https 端口是否开启
     *
     * @return true if the <code>secure</code> port is enabled, false otherwise.
     */
    boolean getSecurePortEnabled();

    /**
     * Indicates how often (in seconds) the eureka client needs to send
     * heartbeats to eureka server to indicate that it is still alive. If the
     * heartbeats are not received for the period specified in
     * {@link #getLeaseExpirationDurationInSeconds()}, eureka server will remove
     * the instance from its view, there by disallowing traffic to this
     * instance.
     *
     * 租约续约频率，单位：秒。
     * 应用不断通过按照该频率发送心跳给 Eureka-Server 以达到续约的作用。
     * 当 Eureka-Server 超过最大频率未收到续约（心跳），契约失效，进行应用移除。
     * 应用移除后，其他应用无法从 Eureka-Server 获取该应用。
     *
     * <p>
     * Note that the instance could still not take traffic if it implements
     * {@link HealthCheckCallback} and then decides to make itself unavailable.
     * </p>
     *
     * @return time in seconds
     */
    int getLeaseRenewalIntervalInSeconds();

    /**
     * Indicates the time in seconds that the eureka server waits since it
     * received the last heartbeat before it can remove this instance from its
     * view and there by disallowing traffic to this instance.
     *
     * 指示eureka服务器在从其视图中删除此实例之前收到最后一次心跳之后等待的时间（以秒为单位），并禁止此流量通过此实例。
     *
     * <p>
     * Setting this value too long could mean that the traffic could be routed
     * to the instance even though the instance is not alive. Setting this value
     * too small could mean, the instance may be taken out of traffic because of
     * temporary network glitches.This value to be set to atleast higher than
     * the value specified in {@link #getLeaseRenewalIntervalInSeconds()}
     * .
     *
     * 将此值设置得太长可能意味着即使实例不活动，流量也可以路由到实例。 将此值设置得太小可能意味着，
     * 由于临时网络故障，实例可能会被取消流量。此值设置为至少高于{@link #getLeaseRenewalIntervalInSeconds（）}中指定的值
     * </p>
     *
     * 租约过期时间，单位：秒
     *
     * @return value indicating time in seconds.
     */
    int getLeaseExpirationDurationInSeconds();

    /**
     * Gets the virtual host name defined for this instance.
     *
     * <p>
     * This is typically the way other instance would find this instance by
     * using the virtual host name.Think of this as similar to the fully
     * qualified domain name, that the users of your services will need to find
     * this instance.
     *
     * 这通常是其他实例通过使用虚拟主机名找到此实例的方式。
     * 请将其视为与完全限定的域名类似，您的服务的用户需要找到此实例。
     * </p>
     *
     * 虚拟主机名。
     * 也可以叫做 VIPAddress 。
     *
     * @return the string indicating the virtual host name which the clients use
     *         to call this service.
     */
    String getVirtualHostName();

    /**
     * Gets the secure virtual host name defined for this instance.
     *
     * <p>
     * This is typically the way other instance would find this instance by
     * using the secure virtual host name.Think of this as similar to the fully
     * qualified domain name, that the users of your services will need to find
     * this instance.
     * </p>
     *
     * 获取为此实例定义的安全虚拟主机名。
     * 这通常是其他实例通过使用安全虚拟主机名查找此实例的方式。
     * 请将其视为与完全限定的域名类似，您的服务的用户需要找到此实例。
     *
     * @return the string indicating the secure virtual host name which the
     *         clients use to call this service.
     */
    String getSecureVirtualHostName();

    /**
     * Gets the <code>AWS autoscaling group name</code> associated with this
     * instance. This information is specifically used in an AWS environment to
     * automatically put an instance out of service after the instance is
     * launched and it has been disabled for traffic..
     *
     * 获取与此实例关联的<code>AWS自动缩放组名称</code>。
     * 此信息专门用于AWS环境中，在启动实例后自动将实例退出服务，并且它已被禁用用于流量。
     *
     * @return the autoscaling group name associated with this instance.
     */
    String getASGName();

    /**
     * Gets the hostname associated with this instance. This is the exact name
     * that would be used by other instances to make calls.
     *
     * 获取与此实例关联的主机名。这是其他实例用来调用的确切名称。
     *
     * @param refresh
     *            true if the information needs to be refetched, false
     *            otherwise.
     * @return hostname of this instance which is identifiable by other
     *         instances for making remote calls.
     */
    String getHostName(boolean refresh);

    /**
     * Gets the metadata name/value pairs associated with this instance. This
     * information is sent to eureka server and can be used by other instances.
     *
     * 获取与此实例关联的元数据名称/值对。此信息被发送到EURKA服务器，并且可以被其他实例使用。
     * 元数据( Metadata )集合
     *
     * @return Map containing application-specific metadata.
     */
    Map<String, String> getMetadataMap();

    /**
     * Returns the data center this instance is deployed. This information is
     * used to get some AWS specific instance information if the instance is
     * deployed in AWS.
     *
     * 返回部署此实例的数据中心。如果实例部署在AWS中，则此信息用于获取某些AWS特定实例信息。
     *
     * @return information that indicates which data center this instance is
     *         deployed in.
     */
    DataCenterInfo getDataCenterInfo();

    /**
     * Get the IPAdress of the instance. This information is for academic
     * purposes only as the communication from other instances primarily happen
     * using the information supplied in {@link #getHostName(boolean)}.
     *
     * 获取实例的IPAdress。此信息仅用于学术目的，
     * 因为来自其他实例的通信主要发生在{@Link SythGeToSTNeNT（boolean）}中提供的信息中。
     *
     * @return the ip address of this instance.
     */
    String getIpAddress();

    /**
     * Gets the relative status page {@link java.net.URL} <em>Path</em> for this
     * instance. The status page URL is then constructed out of the
     * {@link #getHostName(boolean)} and the type of communication - secure or
     * unsecure as specified in {@link #getSecurePort()} and
     * {@link #getNonSecurePort()}.
     *
     * 得到的相对地位的链接页面（@ <路径> java.net.url EM》这个实例。然后构造一个状态页URL链接跳出“# gethostname { }（布尔）
     * 和类型安全的或不安全的通信链路#”为指定的getsecureport（）{ }和{ }”# getnonsecureport（链接）。
     *
     * <p>
     * 它通常用于其他服务的信息目的，以查找该实例的状态。
     * 用户可以提供一个简单的<代码> HTML/COD>指示该实例的当前状态。
     * </p>
     *
     * @return - relative <code>URL</code> that specifies the status page.
     */
    String getStatusPageUrlPath();

    /**
     * Gets the absolute status page {@link java.net.URL} for this instance. The users
     * can provide the {@link #getStatusPageUrlPath()} if the status page
     * resides in the same instance talking to eureka, else in the cases where
     * the instance is a proxy for some other server, users can provide the full
     * {@link java.net.URL}. If the full {@link java.net.URL} is provided it takes precedence.
     *
     * 获取此实例的绝对状态页面{@link java.net.URL}。 如果状态页面位于与eureka交谈的同一实例中，
     * 则用户可以提供{@link #getStatusPageUrlPath（）}，
     * 否则在实例是其他服务器的代理的情况下，用户可以提供完整的{@link java.net.URL}。
     * 如果提供了完整的{@link java.net.URL}，则优先。
     *
     * <p>
     * * It is normally used for informational purposes for other services to
     * find about the status of this instance. Users can provide a simple
     * <code>HTML</code> indicating what is the current status of the instance.
     * . The full {@link java.net.URL} should follow the format
     * http://${eureka.hostname}:7001/ where the value ${eureka.hostname} is
     * replaced at runtime.
     * </p>
     *
     * @return absolute status page URL of this instance.
     */
    String getStatusPageUrl();

    /**
     * Gets the relative home page {@link java.net.URL} <em>Path</em> for this instance.
     * The home page URL is then constructed out of the
     * {@link #getHostName(boolean)} and the type of communication - secure or
     * unsecure as specified in {@link #getSecurePort()} and
     * {@link #getNonSecurePort()}.
     *
     * <p>
     * It is normally used for informational purposes for other services to use
     * it as a landing page.
     * </p>
     *
     * @return relative <code>URL</code> that specifies the home page.
     */
    String getHomePageUrlPath();

    /**
     * Gets the absolute home page {@link java.net.URL} for this instance. The users can
     * provide the {@link #getHomePageUrlPath()} if the home page resides in the
     * same instance talking to eureka, else in the cases where the instance is
     * a proxy for some other server, users can provide the full {@link java.net.URL}. If
     * the full {@link java.net.URL} is provided it takes precedence.
     *
     * <p>
     * It is normally used for informational purposes for other services to use
     * it as a landing page. The full {@link java.net.URL} should follow the format
     * http://${eureka.hostname}:7001/ where the value ${eureka.hostname} is
     * replaced at runtime.
     * </p>
     *
     * @return absolute home page URL of this instance.
     */
    String getHomePageUrl();

    /**
     * Gets the relative health check {@link java.net.URL} <em>Path</em> for this
     * instance. The health check page URL is then constructed out of the
     * {@link #getHostName(boolean)} and the type of communication - secure or
     * unsecure as specified in {@link #getSecurePort()} and
     * {@link #getNonSecurePort()}.
     *
     * 获取此实例的相对运行状况检查{@link java.net.URL} <em> Path </ em>。
     * 然后，健康检查页面URL由{@link #getHostName（boolean）}
     * 和通信类型构成 - 安全或不安全，如{@link #getSecurePort（）}和{@link #getNonSecurePort（）}中所指定。
     *
     * <p>
     * It is normally used for making educated decisions based on the health of
     * the instance - for example, it can be used to determine whether to
     * proceed deployments to an entire farm or stop the deployments without
     * causing further damage.
     *
     * 它通常用于根据实例的运行状况做出有根据的决策 - 例如，它可用于确定是否继续部署到整个服务器场或停止部署而不会造成进一步的损害。
     * </p>
     *
     * @return - relative <code>URL</code> that specifies the health check page.
     */
    String getHealthCheckUrlPath();

    /**
     * Gets the absolute health check page {@link java.net.URL} for this instance. The
     * users can provide the {@link #getHealthCheckUrlPath()} if the health
     * check page resides in the same instance talking to eureka, else in the
     * cases where the instance is a proxy for some other server, users can
     * provide the full {@link java.net.URL}. If the full {@link java.net.URL} is provided it
     * takes precedence.
     *
     * 获取此实例的绝对运行状况检查页面{@link java.net.URL}。该
     * 如果运行状况检查页面位于与eureka通信的同一实例中，则用户可以提供{@link #getHealthCheckUrlPath（）}，
     * 否则，如果实例是某个其他服务器的代理，则用户可以提供完整的{@link java.net.URL}。
     * 如果提供了完整的{@link java.net.URL}，则优先。
     *
     * <p>
     * It is normally used for making educated decisions based on the health of
     * the instance - for example, it can be used to determine whether to
     * proceed deployments to an entire farm or stop the deployments without
     * causing further damage.  The full {@link java.net.URL} should follow the format
     * http://${eureka.hostname}:7001/ where the value ${eureka.hostname} is
     * replaced at runtime.
     * </p>
     *
     * @return absolute health check page URL of this instance.
     */
    String getHealthCheckUrl();

    /**
     * Gets the absolute secure health check page {@link java.net.URL} for this instance.
     * The users can provide the {@link #getSecureHealthCheckUrl()} if the
     * health check page resides in the same instance talking to eureka, else in
     * the cases where the instance is a proxy for some other server, users can
     * provide the full {@link java.net.URL}. If the full {@link java.net.URL} is provided it
     * takes precedence.
     *
     * <p>
     * It is normally used for making educated decisions based on the health of
     * the instance - for example, it can be used to determine whether to
     * proceed deployments to an entire farm or stop the deployments without
     * causing further damage. The full {@link java.net.URL} should follow the format
     * http://${eureka.hostname}:7001/ where the value ${eureka.hostname} is
     * replaced at runtime.
     * </p>
     *
     * @return absolute health check page URL of this instance.
     */
    String getSecureHealthCheckUrl();

    /**
     * An instance's network addresses should be fully expressed in it's {@link DataCenterInfo}.
     * For example for instances in AWS, this will include the publicHostname, publicIp,
     * privateHostname and privateIp, when available. The {@link com.netflix.appinfo.InstanceInfo}
     * will further express a "default address", which is a field that can be configured by the
     * registering instance to advertise it's default address. This configuration allowed
     * for the expression of an ordered list of fields that can be used to resolve the default
     * address. The exact field values will depend on the implementation details of the corresponding
     * implementing DataCenterInfo types.
     *
     * 实例的网络地址应该在{@link DataCenterInfo}中完整表示。
     * 例如，对于AWS中的实例，这将包括publicHostname，publicIp，privateHostname和privateIp（如果可用）。
     * {@link com.netflix.appinfo.InstanceInfo}将进一步表达“默认地址”，该地址可由注册实例配置以通告其默认地址。
     * 此配置允许表达可用于解析默认地址的有序字段列表。 确切的字段值将取决于相应的实现DataCenterInfo类型的实现细节。
     *
     * @return an ordered list of fields that should be used to preferentially
     *         resolve this instance's default address, empty String[] for default.
     */
    String[] getDefaultAddressResolutionOrder();

    /**
     * Get the namespace used to find properties.
     * @return the namespace used to find properties.
     */
    String getNamespace();

}
