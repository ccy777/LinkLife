package com.linklife.trade.lifecycle.timeout;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MQ 客户端可恢复生命周期。Context 启动不等待 Broker；后台持续建立 Producer/Consumer，
 * 从而在 MQ 冷启动故障时保留 Transaction 与 Scheduler fallback 的可用性。
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "linklife.trade.order-timeout.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqOrderTimeoutClientManager implements SmartLifecycle {

    private static final long RETRY_DELAY_MILLIS = 3_000L;

    private final ClientServiceProvider provider;
    private final OrderTimeoutRocketMqProperties properties;
    private final RocketMqOrderTimeoutMessageListener listener;
    private final AtomicReference<Producer> producer = new AtomicReference<>();
    private final AtomicReference<PushConsumer> consumer = new AtomicReference<>();

    private volatile boolean running;
    private ExecutorService initializer;

    public RocketMqOrderTimeoutClientManager(
            ClientServiceProvider provider,
            OrderTimeoutRocketMqProperties properties,
            RocketMqOrderTimeoutMessageListener listener) {
        this.provider = provider;
        this.properties = properties;
        this.listener = listener;
    }

    public SendReceipt send(Message message) throws ClientException {
        Producer current = producer.get();
        if (current == null) {
            throw new IllegalStateException("RocketMQ timeout Producer 尚未就绪");
        }
        return current.send(message);
    }

    public boolean isProducerReady() {
        return producer.get() != null;
    }

    public boolean isConsumerReady() {
        return consumer.get() != null;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        initializer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rocketmq-timeout-client-initializer");
            thread.setDaemon(true);
            return thread;
        });
        initializer.submit(this::initializeLoop);
    }

    private void initializeLoop() {
        while (running) {
            initializeClientsOnce();
            if (!running) return;
            try {
                TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    void initializeClientsOnce() {
        if (!running) return;
        if (producer.get() == null) initializeProducer();
        if (!running) return;
        if (consumer.get() == null) initializeConsumer();
    }

    private void initializeProducer() {
        Producer candidate = null;
        try {
            candidate = provider.newProducerBuilder()
                    .setClientConfiguration(clientConfiguration())
                    .setTopics(properties.getTopic())
                    .build();
            synchronized (this) {
                if (running && producer.compareAndSet(null, candidate)) {
                    log.info("RocketMQ timeout Producer 已就绪");
                    candidate = null;
                }
            }
        } catch (Exception e) {
            log.warn("RocketMQ timeout Producer 初始化失败，将后台重试 errorType={}",
                    e.getClass().getSimpleName());
        } finally {
            closeQuietly(candidate);
        }
    }

    private void initializeConsumer() {
        PushConsumer candidate = null;
        try {
            FilterExpression filter = new FilterExpression(
                    properties.getTag(), FilterExpressionType.TAG);
            candidate = provider.newPushConsumerBuilder()
                    .setClientConfiguration(clientConfiguration())
                    .setConsumerGroup(properties.getConsumerGroup())
                    .setSubscriptionExpressions(Map.of(properties.getTopic(), filter))
                    .setConsumptionThreadCount(properties.getConsumptionThreadCount())
                    .setMessageListener(listener)
                    .build();
            synchronized (this) {
                if (running && consumer.compareAndSet(null, candidate)) {
                    log.info("RocketMQ timeout Consumer 已就绪");
                    candidate = null;
                }
            }
        } catch (Exception e) {
            log.warn("RocketMQ timeout Consumer 初始化失败，将后台重试 errorType={}",
                    e.getClass().getSimpleName());
        } finally {
            closeQuietly(candidate);
        }
    }

    private ClientConfiguration clientConfiguration() {
        return ClientConfiguration.newBuilder()
                .setEndpoints(properties.getEndpoints())
                .enableSsl(properties.isSslEnabled())
                .setRequestTimeout(properties.getRequestTimeout())
                .build();
    }

    @Override
    public synchronized void stop() {
        if (!running && initializer == null && producer.get() == null && consumer.get() == null) return;
        running = false;
        if (initializer != null) {
            initializer.shutdownNow();
            initializer = null;
        }
        closeQuietly(consumer.getAndSet(null));
        closeQuietly(producer.getAndSet(null));
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    private void closeQuietly(AutoCloseable client) {
        if (client == null) return;
        try {
            client.close();
        } catch (Exception e) {
            log.warn("RocketMQ timeout 客户端关闭异常 errorType={}",
                    e.getClass().getSimpleName());
        }
    }
}
