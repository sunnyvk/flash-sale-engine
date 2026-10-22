package com.sunny.flashsale.booking.config;

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

/**
 * Switched on together with the relay (booking.outbox.enabled=false turns off all Kafka messaging in this service). Topics are declared in code (the broker has auto-create off), so a typo in a
 * topic name fails loudly instead of creating a stray topic.
 */
@Configuration
@EnableScheduling
@EnableKafkaRetryTopic
@ConditionalOnProperty(prefix = "booking.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
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

    /**
     * Retry topics park messages by pausing partitions and need a scheduler to resume them. It also runs the outbox
     * relay and the reaper, so it has several threads: the relay's long-running pass must not starve the others.
     */
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
