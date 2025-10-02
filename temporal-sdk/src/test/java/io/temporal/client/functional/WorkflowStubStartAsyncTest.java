package io.temporal.client.functional;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowClientInterceptorBase;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowStubStartAsyncTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowImpl.class).build();

  @WorkflowInterface
  public interface TestWorkflow {

    @WorkflowMethod
    String execute(String input);
  }

  public static class TestWorkflowImpl implements TestWorkflow {

    @Override
    public String execute(String input) {
      return "Hello " + input;
    }
  }

  @Test
  public void startAsyncCompletesWithExecution() throws Exception {
    WorkflowStub stub =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(
                TestWorkflow.class.getSimpleName(),
                WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build());

    CompletableFuture<WorkflowExecution> future = stub.startAsync("Temporal");

    WorkflowExecution execution = future.get(10, TimeUnit.SECONDS);
    assertEquals(stub.getExecution().getWorkflowId(), execution.getWorkflowId());

    assertEquals("Hello Temporal", stub.getResult(String.class));
  }

  @Test
  public void startAsyncPropagatesException() {
    WorkflowStub stub =
        testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub("non-existing-id");

    CompletableFuture<WorkflowExecution> future = stub.startAsync();

    assertTrue(future.isCompletedExceptionally());
    CompletionException completionException = assertThrows(CompletionException.class, future::join);
    assertTrue(completionException.getCause() instanceof IllegalStateException);
  }

  @Test
  public void startAsyncUsesAsyncInterceptor() throws Exception {
    AtomicBoolean startCalled = new AtomicBoolean();
    AtomicBoolean startAsyncCalled = new AtomicBoolean();

    WorkflowClientOptions clientOptions =
        WorkflowClientOptions.newBuilder(testWorkflowRule.getWorkflowClient().getOptions())
            .setInterceptors(
                new WorkflowClientInterceptorBase() {
                  @Override
                  public WorkflowClientCallsInterceptor workflowClientCallsInterceptor(
                      WorkflowClientCallsInterceptor next) {
                    return new WorkflowClientCallsInterceptorBase(next) {
                      @Override
                      public WorkflowStartOutput start(WorkflowStartInput input) {
                        startCalled.set(true);
                        return super.start(input);
                      }

                      @Override
                      public CompletableFuture<WorkflowStartOutput> startAsync(
                          WorkflowStartInput input) {
                        startAsyncCalled.set(true);
                        return super.startAsync(input);
                      }
                    };
                  }
                })
            .validateAndBuildWithDefaults();

    WorkflowClient client =
        WorkflowClient.newInstance(testWorkflowRule.getWorkflowServiceStubs(), clientOptions);

    WorkflowStub stub =
        client.newUntypedWorkflowStub(
            TestWorkflow.class.getSimpleName(),
            WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build());

    CompletableFuture<WorkflowExecution> future = stub.startAsync("Temporal");

    WorkflowExecution execution = future.get(10, TimeUnit.SECONDS);
    assertEquals(stub.getExecution().getWorkflowId(), execution.getWorkflowId());
    assertTrue(startAsyncCalled.get());
    assertFalse(startCalled.get());
  }
}
