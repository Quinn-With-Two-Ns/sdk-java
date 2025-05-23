package io.temporal.workflow;

import io.temporal.client.WorkflowException;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class WorkflowRetryDoNotRetryExceptionTest {

  private static final Map<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowRetryDoNotRetryException.class)
          .build();

  @Test
  public void testWorkflowRetryDoNotRetryException() {
    RetryOptions workflowRetryOptions =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setDoNotRetry("NonRetryable")
            .setMaximumAttempts(100)
            .setBackoffCoefficient(1.0)
            .build();
    TestWorkflow1 workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflow1.class,
                SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
                    .toBuilder()
                    .setRetryOptions(workflowRetryOptions)
                    .build());
    try {
      workflowStub.execute(testName.getMethodName());
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause() instanceof ApplicationFailure);
      Assert.assertEquals("NonRetryable", ((ApplicationFailure) e.getCause()).getType());
      Assert.assertEquals(
          "message='simulated 3', type='NonRetryable', nonRetryable=false",
          e.getCause().getMessage());
    }
  }

  public static class TestWorkflowRetryDoNotRetryException implements TestWorkflow1 {

    @Override
    public String execute(String testName) {
      AtomicInteger count = retryCount.get(testName);
      if (count == null) {
        count = new AtomicInteger();
        retryCount.put(testName, count);
      }
      int c = count.incrementAndGet();
      if (c < 3) {
        throw new IllegalArgumentException("simulated " + c);
      } else {
        throw ApplicationFailure.newFailure("simulated " + c, "NonRetryable");
      }
    }
  }
}
