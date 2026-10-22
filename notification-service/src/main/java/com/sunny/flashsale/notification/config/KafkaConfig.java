package com.sunny.flashsale.notification.config;

import com.sunny.flashsale.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaRetryTopic;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Declares the main topic (the broker has auto-create off) so the consumer can start before the producer has ever run.
 * The retry and dead-letter topics are created by {@code @RetryableTopic}.
 */
@Configuration
@EnableKafkaRetryTopic
public class KafkaConfig {

    @Bean
    public NewTopic bookingEventsTopic(@Value("${kafka.topics.partitions:3}") int partitions,
                                       @Value("${kafka.topics.replicas:1}") int replicas) {
        return TopicBuilder.name(Topics.BOOKING_EVENTS).partitions(partitions).replicas(replicas).build();
    }

    /**
     * Retry topics are non-blocking: a message waiting for its back-off is parked by pausing the partition, and this
     * scheduler resumes it when the delay is over. Spring Kafka refuses to start without one.
     */
    @Bean
    public TaskScheduler retryTopicScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("retry-topic-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
