package io.temporal.workflow.activityTests;

import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowException;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ActivityRetryOptionsChangeTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestActivityRetryOptionsChange.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testActivityRetryOptionsChange() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    try {
      workflowStub.execute(testWorkflowRule.getTaskQueue());
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          IOException.class.getName(), ((ApplicationFailure) e.getCause().getCause()).getType());
    }
    Assert.assertEquals(activitiesImpl.toString(), 2, activitiesImpl.invocations.size());
  }

  public static class TestActivityRetryOptionsChange implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      ActivityOptions.Builder options =
          ActivityOptions.newBuilder()
              .setTaskQueue(taskQueue)
              .setHeartbeatTimeout(Duration.ofSeconds(5))
              .setScheduleToCloseTimeout(Duration.ofDays(5))
              .setScheduleToStartTimeout(Duration.ofSeconds(1))
              .setStartToCloseTimeout(Duration.ofSeconds(1))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build());
      RetryOptions retryOptions;
      if (WorkflowUnsafe.isReplaying()) {
        retryOptions =
            RetryOptions.newBuilder()
                .setMaximumInterval(Duration.ofSeconds(1))
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(3)
                .build();
      } else {
        retryOptions = RetryOptions.newBuilder().setMaximumAttempts(2).build();
      }
      TestActivities.VariousTestActivities activities =
          Workflow.newActivityStub(TestActivities.VariousTestActivities.class, options.build());
      Workflow.retry(retryOptions, Optional.of(Duration.ofDays(1)), () -> activities.throwIO());
      return "ignored";
    }
  }
}
