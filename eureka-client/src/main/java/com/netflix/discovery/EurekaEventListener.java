package com.netflix.discovery;

/**
 * Listener for receiving {@link EurekaClient} events such as {@link StatusChangeEvent}.  Register
 * a listener by calling {@link EurekaClient#registerEventListener(EurekaEventListener)}
 *
 * 监听器接收{@link EurekaClient}事件，例如{@link StatusChangeEvent}。
 * 通过调用{@link EurekaClient #registerEventListener（EurekaEventListener）}注册一个监听器
 */
public interface EurekaEventListener {
    /**
     * Notification of an event within the {@link EurekaClient}.  
     * 
     * {@link EurekaEventListener#onEvent} is called from the context of an internal eureka thread 
     * and must therefore return as quickly as possible without blocking.
     * 
     * @param event
     */
    public void onEvent(EurekaEvent event);
}
