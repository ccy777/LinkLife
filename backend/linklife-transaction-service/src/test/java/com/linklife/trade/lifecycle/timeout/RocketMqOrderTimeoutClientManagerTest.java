package com.linklife.trade.lifecycle.timeout;

import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.consumer.PushConsumerBuilder;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.ProducerBuilder;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RocketMqOrderTimeoutClientManagerTest {

    private ClientServiceProvider provider;
    private ProducerBuilder producerBuilder;
    private PushConsumerBuilder consumerBuilder;
    private Producer producer;
    private PushConsumer consumer;
    private RocketMqOrderTimeoutClientManager manager;

    @BeforeEach
    void setUp() throws Exception {
        provider = mock(ClientServiceProvider.class);
        producerBuilder = mock(ProducerBuilder.class);
        consumerBuilder = mock(PushConsumerBuilder.class);
        producer = mock(Producer.class);
        consumer = mock(PushConsumer.class);
        RocketMqOrderTimeoutMessageListener listener = mock(RocketMqOrderTimeoutMessageListener.class);
        OrderTimeoutRocketMqProperties properties = new OrderTimeoutRocketMqProperties();
        properties.setEnabled(true);
        properties.setEndpoints("127.0.0.1:8081");

        when(provider.newProducerBuilder()).thenReturn(producerBuilder);
        when(producerBuilder.setClientConfiguration(any())).thenReturn(producerBuilder);
        when(producerBuilder.setTopics(any(String[].class))).thenReturn(producerBuilder);
        when(producerBuilder.build()).thenReturn(producer);
        when(provider.newPushConsumerBuilder()).thenReturn(consumerBuilder);
        when(consumerBuilder.setClientConfiguration(any())).thenReturn(consumerBuilder);
        when(consumerBuilder.setConsumerGroup(any())).thenReturn(consumerBuilder);
        when(consumerBuilder.setSubscriptionExpressions(any(Map.class))).thenReturn(consumerBuilder);
        when(consumerBuilder.setConsumptionThreadCount(anyInt())).thenReturn(consumerBuilder);
        when(consumerBuilder.setMessageListener(listener)).thenReturn(consumerBuilder);
        when(consumerBuilder.build()).thenReturn(consumer);

        manager = new RocketMqOrderTimeoutClientManager(provider, properties, listener);
    }

    @Test
    void initializesBothClientsAndDelegatesConfirmedSend() throws Exception {
        Message message = mock(Message.class);
        SendReceipt receipt = mock(SendReceipt.class);
        when(producer.send(message)).thenReturn(receipt);

        manager.start();
        awaitReady();

        assertThat(manager.isProducerReady()).isTrue();
        assertThat(manager.isConsumerReady()).isTrue();
        assertThat(manager.send(message)).isSameAs(receipt);
        manager.stop();
        verify(producer).close();
        verify(consumer).close();
    }

    @Test
    void unavailableBrokerDoesNotEscapeInitializerAndSendRemainsRetryable() throws Exception {
        when(producerBuilder.build()).thenThrow(new IllegalStateException("broker down"));
        when(consumerBuilder.build()).thenThrow(new IllegalStateException("broker down"));

        assertThatCode(manager::start).doesNotThrowAnyException();
        verify(producerBuilder, timeout(1_000)).build();
        assertThat(manager.isProducerReady()).isFalse();
        assertThat(manager.isConsumerReady()).isFalse();
        assertThatThrownBy(() -> manager.send(mock(Message.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未就绪");
        manager.stop();
    }

    @Test
    void clientFinishingAfterStopIsClosedInsteadOfPublished() throws Exception {
        CountDownLatch buildEntered = new CountDownLatch(1);
        CountDownLatch allowBuildToFinish = new CountDownLatch(1);
        when(producerBuilder.build()).thenAnswer(invocation -> {
            buildEntered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = allowBuildToFinish.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // 模拟第三方 build 在 shutdownNow 后仍然完成，验证生命周期竞态不泄漏客户端。
                }
            }
            return producer;
        });

        manager.start();
        assertThat(buildEntered.await(1, TimeUnit.SECONDS)).isTrue();
        manager.stop();
        allowBuildToFinish.countDown();

        verify(producer, timeout(1_000)).close();
        assertThat(manager.isProducerReady()).isFalse();
        assertThat(manager.isConsumerReady()).isFalse();
    }

    private void awaitReady() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline
                && (!manager.isProducerReady() || !manager.isConsumerReady())) {
            Thread.sleep(10L);
        }
    }
}
