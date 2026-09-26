package com.lld.messaging.notification.channel;

import com.lld.messaging.notification.model.Channel;
import com.lld.messaging.notification.model.Contact;
import com.lld.messaging.notification.template.RenderedMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Stand-in provider for the demo and tests: records what was "sent" and can be told to fail the
 * next few calls, temporarily or permanently, to exercise retries and fallbacks.
 */
public final class FakeSender implements ChannelSender {

    private final Channel channel;
    private final List<String> outbox = Collections.synchronizedList(new ArrayList<>());
    private final Deque<SendFailure> scriptedFailures = new ArrayDeque<>();
    private int counter;

    public FakeSender(Channel channel) {
        this.channel = channel;
    }

    /** The next {@code times} calls fail with this error. */
    public synchronized FakeSender failNext(int times, SendFailure failure) {
        for (int i = 0; i < times; i++) {
            scriptedFailures.addLast(failure);
        }
        return this;
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public String send(Contact to, RenderedMessage message) throws SendFailure {
        synchronized (this) {
            if (!scriptedFailures.isEmpty()) {
                throw scriptedFailures.pollFirst();
            }
            counter++;
        }
        outbox.add(to.userId() + ": " + (message.subject().isEmpty() ? "" : "[" + message.subject() + "] ") + message.body());
        return channel.name().toLowerCase() + "-" + counter;
    }

    public List<String> outbox() {
        synchronized (outbox) {
            return List.copyOf(outbox);
        }
    }
}
