package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionTest extends BaseVersionTest {

  private static boolean hasReplayed;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestGetVersionWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testGetVersion() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertTrue(hasReplayed);
    assertEquals("activity22activity1activity1activity1", result);
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "getVersion",
            "executeActivity Activity2",
            "activity Activity2",
            "getVersion",
            "executeActivity customActivity1",
            "activity customActivity1",
            "executeActivity customActivity1",
            "activity customActivity1",
            "sleep PT1S",
            "getVersion",
            "executeActivity customActivity1",
            "activity customActivity1");
  }

  @Test
  public void testGetVersionReplay() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testGetVersionHistory.json", TestGetVersionWorkflowImpl.class);
  }

  public static class TestGetVersionWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));

      // Test adding a version check in non-replay code.
      int version = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 1);
      assertEquals(version, 1);
      String result = testActivities.activity2("activity2", 2);

      // Test version change in non-replay code.
      version = Workflow.getVersion("test_change", 1, 2);
      assertEquals(version, 1);
      result += "activity" + testActivities.activity1(1);

      // Test adding a version check in replay code.
      if (WorkflowUnsafe.isReplaying()) {
        hasReplayed = true;
        int version2 = Workflow.getVersion("test_change_2", Workflow.DEFAULT_VERSION, 1);
        assertEquals(version2, Workflow.DEFAULT_VERSION);
      }
      result += "activity" + testActivities.activity1(1); // This is executed in non-replay mode.

      // Test get version in replay mode.
      Workflow.sleep(1000);
      version = Workflow.getVersion("test_change", 1, 2);
      assertEquals(version, 1);
      result += "activity" + testActivities.activity1(1);
      return result;
    }
  }
}
