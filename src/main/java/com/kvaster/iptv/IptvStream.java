package com.kvaster.iptv;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import io.undertow.io.IoCallback;
import io.undertow.io.Sender;
import io.undertow.server.HttpServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IptvStream implements Subscriber<List<ByteBuffer>> {
    private static final Logger LOG = LoggerFactory.getLogger(IptvStream.class);

    private final HttpServerExchange exchange;

    private Queue<ByteBuffer> buffers = new LinkedBlockingQueue<>();
    private AtomicBoolean busy = new AtomicBoolean();

    private Subscription subscription;

    private static ByteBuffer END_MARKER = ByteBuffer.allocate(0);
    private static List<ByteBuffer> END_ARRAY_MARKER = Collections.singletonList(END_MARKER);

    public IptvStream(HttpServerExchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
    }

    private void finish() {
        // subscription can't be null at this place
        subscription.cancel();

        onNext(END_ARRAY_MARKER);
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
        buffers.addAll(item);

        if (busy.compareAndSet(false, true)) {
            sendNext();
        }

        subscription.request(1);
    }

    private void sendNext() {
        ByteBuffer b;
        while ((b = buffers.poll()) != null) {
            if (!sendNext(b)) {
                return;
            }
        }

        busy.set(false);
    }

    private boolean sendNext(ByteBuffer b) {
        if (b == END_MARKER) {
            exchange.endExchange();
            return true;
        }

        AtomicBoolean completed = new AtomicBoolean(false);

        exchange.getResponseSender().send(b, new IoCallback() {
            @Override
            public void onComplete(HttpServerExchange exchange, Sender sender) {
                if (!completed.compareAndSet(false, true)) {
                    sendNext();
                }
            }

            @Override
            public void onException(HttpServerExchange exchange, Sender sender, IOException exception) {
                finish();
            }
        });

        return !completed.compareAndSet(false, true);
    }

    @Override
    public void onError(Throwable throwable) {
        LOG.warn("Error on loading stream", throwable);
        finish();
    }

    @Override
    public void onComplete() {
        // LOG.info("onComplete");
        finish();
    }
}