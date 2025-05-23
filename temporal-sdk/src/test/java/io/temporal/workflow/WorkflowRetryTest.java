package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class WorkflowRetryTest {

  private static final Map<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowRetryImpl.class).build();

  @Test
  public void testWorkflowRetry() {
    RetryOptions workflowRetryOptions =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setMaximumAttempts(3)
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
    long start = testWorkflowRule.getTestEnvironment().currentTimeMillis();
    try {
      workflowStub.execute(testName.getMethodName());
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertEquals(
          e.toString(),
          "message='simulated 3', type='test', nonRetryable=false",
          e.getCause().getMessage());
    } finally {
      long elapsed = testWorkflowRule.getTestEnvironment().currentTimeMillis() - start;
      Assert.assertTrue(
          String.valueOf(elapsed), elapsed >= 2000); // Ensure that retry delays the restart
      // Verify that the first workflow task backoff is set to 1 second
      WorkflowExecution workflowExecution = WorkflowStub.fromTyped(workflowStub).getExecution();
      WorkflowExecutionHistory workflowExecutionHistory =
          testWorkflowRule.getWorkflowClient().fetchHistory(workflowExecution.getWorkflowId());
      List<WorkflowExecutionStartedEventAttributes> workflowExecutionStartedEvents =
          workflowExecutionHistory.getEvents().stream()
              .filter(HistoryEvent::hasWorkflowExecutionStartedEventAttributes)
              .map(x -> x.getWorkflowExecutionStartedEventAttributes())
              .collect(Collectors.toList());
      assertEquals(1, workflowExecutionStartedEvents.size());
      assertEquals(
          Duration.ofSeconds(1),
          ProtobufTimeUtils.toJavaDuration(
              workflowExecutionStartedEvents.get(0).getFirstWorkflowTaskBackoff()));
    }
  }

  public static class TestWorkflowRetryImpl implements TestWorkflow1 {

    @Override
    public String execute(String testName) {
      AtomicInteger count = retryCount.get(testName);
      if (count == null) {
        count = new AtomicInteger();
        retryCount.put(testName, count);
      }
      int attempt = Workflow.getInfo().getAttempt();
      assertEquals(count.get() + 1, attempt);
      throw ApplicationFailure.newFailure("simulated " + count.incrementAndGet(), "test");
    }
  }
}
