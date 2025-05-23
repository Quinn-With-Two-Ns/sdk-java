package io.temporal.internal.worker;

import static org.junit.Assert.assertEquals;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.testUtils.CountingSlotSupplier;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.tuning.*;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class WorkflowSlotsSmallSizeTests {
  private final int MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE = 2;
  private final int MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE = 2;
  private final int MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE = 2;
  private final int MAX_CONCURRENT_NEXUS_EXECUTION_SIZE = 2;
  private final CountingSlotSupplier<WorkflowSlotInfo> workflowTaskSlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE);
  private final CountingSlotSupplier<ActivitySlotInfo> activityTaskSlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE);
  private final CountingSlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE);
  private final CountingSlotSupplier<NexusSlotInfo> nexusSlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_NEXUS_EXECUTION_SIZE);
  static Semaphore parallelSemRunning = new Semaphore(0);
  static Semaphore parallelSemBlocked = new Semaphore(0);

  @Parameterized.Parameter public boolean activitiesAreLocal;

  @Parameterized.Parameters()
  public static Object[] data() {
    return new Object[][] {{true}, {false}};
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setWorkerTuner(
                      new CompositeTuner(
                          workflowTaskSlotSupplier,
                          activityTaskSlotSupplier,
                          localActivitySlotSupplier,
                          nexusSlotSupplier))
                  .build())
          .setActivityImplementations(new TestActivitySemaphoreImpl())
          .setWorkflowTypes(ParallelActivities.class)
          .setDoNotStart(true)
          .build();

  @Before
  public void setup() {
    parallelSemRunning = new Semaphore(0);
    parallelSemBlocked = new Semaphore(0);
  }

  @After
  public void tearDown() {
    testWorkflowRule.getTestEnvironment().close();
    assertEquals(
        workflowTaskSlotSupplier.reservedCount.get(), workflowTaskSlotSupplier.releasedCount.get());
    assertEquals(
        activityTaskSlotSupplier.reservedCount.get(), activityTaskSlotSupplier.releasedCount.get());
    assertEquals(
        localActivitySlotSupplier.reservedCount.get(),
        localActivitySlotSupplier.releasedCount.get());
  }

  private void assertCurrentUsedCount(int activity, int localActivity) {
    assertEquals(activity, activityTaskSlotSupplier.currentUsedSet.size());
    assertEquals(localActivity, localActivitySlotSupplier.currentUsedSet.size());
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(boolean useLocalActivity);

    @SignalMethod
    void unblock();
  }

  public static class ParallelActivities implements TestWorkflow {
    boolean unblocked = false;

    private final TestActivity activity =
        Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .validateAndBuildWithDefaults());

    private final TestActivity localActivity =
        Workflow.newLocalActivityStub(
            TestActivity.class,
            LocalActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .validateAndBuildWithDefaults());

    @Override
    public String workflow(boolean useLocalActivity) {
      Workflow.await(() -> unblocked);
      List<Promise<String>> laResults = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        if (useLocalActivity) {
          laResults.add(Async.function(localActivity::activity, String.valueOf(i)));
        } else {
          laResults.add(Async.function(activity::activity, String.valueOf(i)));
        }
      }
      Promise.allOf(laResults).get();
      return "ok";
    }

    @Override
    public void unblock() {
      unblocked = true;
    }
  }

  @ActivityInterface
  public interface TestActivity {

    @ActivityMethod
    String activity(String input);
  }

  public static class TestActivitySemaphoreImpl implements TestActivity {
    @Override
    public String activity(String input) {
      parallelSemRunning.release();
      try {
        parallelSemBlocked.acquire();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      return "";
    }
  }

  private void assertIntraWFTSlotCount(int allowedToRun) {
    int runningLAs = activitiesAreLocal ? allowedToRun : 0;
    int runningAs = activitiesAreLocal ? 0 : allowedToRun;
    assertCurrentUsedCount(runningAs, runningLAs);
  }

  @Test
  public void TestActivitySlotAtLimit() throws InterruptedException {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::workflow, activitiesAreLocal);
    workflow.unblock();
    for (int i = 0; i < 5; i++) {
      parallelSemRunning.acquire(2);
      assertIntraWFTSlotCount(2);
      parallelSemBlocked.release(2);
    }
    workflow.workflow(true);
    // All slots should be available
    assertCurrentUsedCount(0, 0);
  }

  @Test
  public void TestActivityShutdownWhileWaitingOnSlot() throws InterruptedException {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::workflow, activitiesAreLocal);
    workflow.unblock();
    parallelSemRunning.acquire(2);
    testWorkflowRule.getTestEnvironment().getWorkerFactory().shutdownNow();
    parallelSemBlocked.release(2);
    testWorkflowRule.getTestEnvironment().getWorkerFactory().awaitTermination(3, TimeUnit.SECONDS);
    // All slots should be available
    // Used count here is actually -2 since the slots weren't marked used
    assertCurrentUsedCount(0, 0);
  }

  @Test
  public void TestActivitySlotHitsCapacity() throws InterruptedException {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowTaskTimeout(Duration.ofSeconds(1))
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::workflow, activitiesAreLocal);
    workflow.unblock();
    for (int i = 0; i < 5; i++) {
      parallelSemRunning.acquire(2);
      assertIntraWFTSlotCount(2);
      parallelSemBlocked.release(2);
      // Take too long (hit WFT timeout while trying to schedule LAs)
      if (i == 2) {
        Thread.sleep(1000);
      }
    }
    // Because the WFT fails, the LAs may be re-run, and it's not clearly defined how many of them
    // will, so ensure there are enough permits for the test to complete. What matters is that the
    // slot counts end up at the appropriate values after everything finishes.
    parallelSemBlocked.release(100);
    workflow.workflow(true);
    // All slots should be available
    assertCurrentUsedCount(0, 0);
  }
}
