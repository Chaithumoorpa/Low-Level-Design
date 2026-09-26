package com.lld.messaging.chat.server;

import com.lld.messaging.chat.model.MessageView;

/**
 * Observer for members with no open session: the bridge to mobile push notifications
 * (e.g. the Notification System in this folder). Not called for muted conversations.
 */
@FunctionalInterface
public interface OfflineNotifier {

    void notifyOffline(String userId, MessageView message);
}
