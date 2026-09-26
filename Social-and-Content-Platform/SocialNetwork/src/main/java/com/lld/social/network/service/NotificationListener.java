package com.lld.social.network.service;

/** Observer: push notifications, e-mail digests, the bell icon. */
@FunctionalInterface
public interface NotificationListener {

    void notify(String userId, String text);
}
