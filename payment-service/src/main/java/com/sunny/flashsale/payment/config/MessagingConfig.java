package com.sunny.flashsale.payment.config;

import com.sunny.flashsale.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaRetryTopic;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Everything that needs Kafka, switched on and off together by {@code payment.outbox.enabled}. */
@Configuration
@EnableScheduling
@EnableKafkaRetryTopic
@ConditionalOnProperty(prefix = "payment.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MessagingConfig {

    @Bean
    public NewTopic bookingEventsTopic(@Value("${kafka.topics.partitions:3}") int partitions,
                                       @Value("${kafka.topics.replicas:1}") int replicas) {
        return TopicBuilder.name(Topics.BOOKING_EVENTS).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic paymentEventsTopic(@Value("${kafka.topics.partitions:3}") int partitions,
                                       @Value("${kafka.topics.replicas:1}") int replicas) {
        return TopicBuilder.name(Topics.PAYMENT_EVENTS).partitions(partitions).replicas(replicas).build();
    }

    /** Needed by non-blocking retry topics (they park messages by pausing partitions) and used by the outbox relay. */
    @Bean
    public TaskScheduler retryTopicScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
