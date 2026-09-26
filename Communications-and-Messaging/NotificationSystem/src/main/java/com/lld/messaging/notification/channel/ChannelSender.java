package com.lld.messaging.notification.channel;

import com.lld.messaging.notification.model.Channel;
import com.lld.messaging.notification.model.Contact;
import com.lld.messaging.notification.template.RenderedMessage;

/**
 * Adapter over one delivery provider (SMTP server, SMS gateway, APNs/FCM, in-app inbox).
 * The rest of the system never sees provider APIs, so providers can be swapped or mocked.
 */
public interface ChannelSender {

    Channel channel();

    /**
     * @return a provider reference (message id) on success
     * @throws SendFailure if the provider refused; {@link SendFailure#permanent()} says whether retrying can help
     */
    String send(Contact to, RenderedMessage message) throws SendFailure;
}
