package io.temporal.workerFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class WorkerFactoryTests {

  private static final boolean useDockerService =
      Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE"));
  private static final String serviceAddress = System.getenv("TEMPORAL_SERVICE_ADDRESS");

  @BeforeClass
  public static void beforeClass() {
    Assume.assumeTrue(useDockerService);
  }

  private WorkflowServiceStubs service;
  private WorkerFactory factory;

  @Before
  public void setUp() {
    service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(serviceAddress).build());
    WorkflowClient client = WorkflowClient.newInstance(service);
    factory = WorkerFactory.newInstance(client);
  }

  @After
  public void tearDown() throws InterruptedException {
    factory.shutdownNow();
    factory.awaitTermination(5, TimeUnit.SECONDS);
    service.shutdownNow();
    service.awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  public void whenAFactoryIsStartedAllWorkersStart() {
    factory.newWorker("task1");
    factory.newWorker("task2");

    factory.start();
    assertTrue(factory.isStarted());
    factory.shutdown();
    factory.awaitTermination(1, TimeUnit.SECONDS);
  }

  @Test
  public void whenAFactoryIsShutdownAllWorkersAreShutdown() {
    factory.newWorker("task1");
    factory.newWorker("task2");

    assertFalse(factory.isStarted());
    factory.start();
    assertTrue(factory.isStarted());
    assertFalse(factory.isShutdown());
    factory.shutdown();
    factory.awaitTermination(1, TimeUnit.MILLISECONDS);

    assertTrue(factory.isShutdown());
    factory.shutdown();
    assertTrue(factory.isShutdown());
    factory.awaitTermination(1, TimeUnit.SECONDS);
  }

  @Test
  public void aFactoryCanBeStartedMoreThanOnce() {
    factory.start();
    factory.start();
    factory.shutdown();
    factory.awaitTermination(1, TimeUnit.SECONDS);
  }

  @Test(expected = IllegalStateException.class)
  public void aFactoryCannotBeStartedAfterShutdown() {
    factory.newWorker("task1");

    factory.shutdown();
    factory.awaitTermination(1, TimeUnit.MILLISECONDS);
    factory.start();
  }

  @Test(expected = IllegalStateException.class)
  public void workersCannotBeCreatedAfterFactoryHasStarted() {
    factory.newWorker("task1");

    factory.start();

    try {
      factory.newWorker("task2");
    } finally {
      factory.shutdown();
      factory.awaitTermination(1, TimeUnit.SECONDS);
    }
  }

  @Test(expected = IllegalStateException.class)
  public void workersCannotBeCreatedAfterFactoryIsShutdown() {
    factory.newWorker("task1");

    factory.shutdown();
    factory.awaitTermination(1, TimeUnit.MILLISECONDS);
    try {
      factory.newWorker("task2");
    } finally {
      factory.shutdown();
      factory.awaitTermination(1, TimeUnit.SECONDS);
    }
  }

  @Test
  public void factoryCanBeShutdownMoreThanOnce() {
    factory.newWorker("task1");

    factory.shutdown();
    factory.awaitTermination(1, TimeUnit.MILLISECONDS);
    factory.shutdown();
    factory.awaitTermination(1, TimeUnit.MILLISECONDS);
  }
}
