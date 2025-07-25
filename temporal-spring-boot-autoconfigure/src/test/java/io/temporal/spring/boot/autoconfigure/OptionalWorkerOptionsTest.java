package io.temporal.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.temporal.client.WorkflowClient;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.tuning.PollerBehaviorAutoscaling;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = OptionalWorkerOptionsTest.Configuration.class)
@ActiveProfiles(profiles = {"optional-workers-options"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OptionalWorkerOptionsTest {
  @Autowired ConfigurableApplicationContext applicationContext;
  @Autowired TestWorkflowEnvironment testWorkflowEnvironment;
  @Autowired WorkflowClient workflowClient;

  @Autowired TemporalOptionsCustomizer<WorkerFactoryOptions.Builder> workerFactoryCustomizer;
  @Autowired TemporalOptionsCustomizer<WorkerOptions.Builder> workerCustomizer;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void testOptionalVariablesFromTheConfigAreRespected() {
    // Checking that customizers were called, the actual value checks are located in these
    // customizers
    verify(workerFactoryCustomizer).customize(any());
    verify(workerCustomizer).customize(any());
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.byworkername\\..*",
              type = FilterType.REGEX))
  public static class Configuration {

    @Bean
    @SuppressWarnings("unchecked")
    public TemporalOptionsCustomizer<WorkerFactoryOptions.Builder> workerFactoryCustomizer() {
      TemporalOptionsCustomizer<WorkerFactoryOptions.Builder> customizer =
          optionsBuilder -> {
            WorkerFactoryOptions options = optionsBuilder.build();

            assertEquals(
                10,
                options.getMaxWorkflowThreadCount(),
                "Values from the Spring Config should be respected");
            assertEquals(
                10,
                options.getWorkflowCacheSize(),
                "Values from the Spring Config should be respected");
            return optionsBuilder;
          };
      return mock(TemporalOptionsCustomizer.class, delegatesTo(customizer));
    }

    @Bean
    @SuppressWarnings("unchecked")
    public TemporalOptionsCustomizer<WorkerOptions.Builder> workerCustomizer() {
      TemporalOptionsCustomizer<WorkerOptions.Builder> customizer =
          optionsBuilder -> {
            WorkerOptions options = optionsBuilder.build();

            assertEquals(
                1,
                options.getMaxConcurrentWorkflowTaskExecutionSize(),
                "Values from the Spring Config should be respected");
            assertEquals(
                1,
                options.getMaxConcurrentActivityExecutionSize(),
                "Values from the Spring Config should be respected");
            assertEquals(
                1,
                options.getMaxConcurrentLocalActivityExecutionSize(),
                "Values from the Spring Config should be respected");
            assertEquals(
                1,
                options.getMaxConcurrentNexusExecutionSize(),
                "Values from the Spring Config should be respected");

            assertNotNull(options.getWorkflowTaskPollersBehavior());
            assertInstanceOf(
                PollerBehaviorAutoscaling.class, options.getWorkflowTaskPollersBehavior());
            PollerBehaviorAutoscaling autoscaling =
                (PollerBehaviorAutoscaling) options.getWorkflowTaskPollersBehavior();
            assertEquals(
                1,
                autoscaling.getMinConcurrentTaskPollers(),
                "Values from the Spring Config should be respected");
            assertEquals(
                10,
                autoscaling.getMaxConcurrentTaskPollers(),
                "Values from the Spring Config should be respected");
            assertEquals(
                5,
                autoscaling.getInitialConcurrentTaskPollers(),
                "Values from the Spring Config should be respected");
            assertNotNull(options.getActivityTaskPollersBehavior());
            assertInstanceOf(
                PollerBehaviorAutoscaling.class, options.getActivityTaskPollersBehavior());
            autoscaling = (PollerBehaviorAutoscaling) options.getActivityTaskPollersBehavior();
            assertEquals(
                1,
                autoscaling.getMinConcurrentTaskPollers(),
                "Values from the Spring Config should be respected");
            assertEquals(
                100,
                autoscaling.getMaxConcurrentTaskPollers(),
                "Values from the Spring Config should be respected");
            assertEquals(
                5,
                autoscaling.getInitialConcurrentTaskPollers(),
                "Values from the Spring Config should be respected");
            assertEquals(
                1,
                options.getMaxConcurrentNexusTaskPollers(),
                "Values from the Spring Config should be respected");

            assertEquals(
                1.0,
                options.getMaxWorkerActivitiesPerSecond(),
                "Values from the Spring Config should be respected");
            assertEquals(
                1.0,
                options.getMaxTaskQueueActivitiesPerSecond(),
                "Values from the Spring Config should be respected");

            assertEquals(
                "1.0.0", options.getBuildId(), "Values from the Spring Config should be respected");
            assertTrue(
                options.isUsingBuildIdForVersioning(),
                "Values from the Spring Config should be respected");
            return optionsBuilder;
          };
      return mock(TemporalOptionsCustomizer.class, delegatesTo(customizer));
    }
  }
}
