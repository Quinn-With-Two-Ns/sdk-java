package io.temporal.testing;

import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.nexusrpc.Header;
import io.nexusrpc.ServiceDefinition;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.nexus.v1.*;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptorBase;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.nexus.NexusTaskHandlerImpl;
import io.temporal.internal.sync.*;
import io.temporal.internal.testservice.InProcessGRPCServer;
import io.temporal.internal.worker.NexusTask;
import io.temporal.internal.worker.NexusTaskHandler;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.Workflow;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TestNexusOperationEnvironmentInternal implements TestNexusOperationEnvironment {
  private static final String TEST_ENDPOINT = "test-endpoint";
  private static final Logger log =
      LoggerFactory.getLogger(TestNexusOperationEnvironmentInternal.class);
  private final ExecutorService nexusWorkerExecutor =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "test-service-nexus-worker"));
  private final ExecutorService deterministicRunnerExecutor =
      new ThreadPoolExecutor(
          1,
          1000,
          1,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          r -> new Thread(r, "test-service--nexus-deterministic-runner"));

  private final NexusTaskHandlerImpl nexusTaskHandler;
  private final TestEnvironmentOptions testEnvironmentOptions;
  private final InProcessGRPCServer mockServer;
  private final WorkflowServiceStubs workflowServiceStubs;
  private final AtomicBoolean started = new AtomicBoolean();



  public TestNexusOperationEnvironmentInternal(TestEnvironmentOptions options) {
    // Initialize an in-memory mock service.
    this.mockServer =
            new InProcessGRPCServer(Collections.singletonList(new TestActivityEnvironmentInternal.HeartbeatInterceptingService()));
    this.testEnvironmentOptions =
            options != null
                    ? TestEnvironmentOptions.newBuilder(options).validateAndBuildWithDefaults()
                    : TestEnvironmentOptions.newBuilder().validateAndBuildWithDefaults();
    WorkflowServiceStubsOptions.Builder serviceStubsOptionsBuilder =
            WorkflowServiceStubsOptions.newBuilder(
                            testEnvironmentOptions.getWorkflowServiceStubsOptions())
                    .setTarget(null)
                    .setChannel(this.mockServer.getChannel())
                    .setRpcQueryTimeout(Duration.ofSeconds(60));
    Scope metricsScope = this.testEnvironmentOptions.getMetricsScope();
    if (metricsScope != null && !(NoopScope.class.equals(metricsScope.getClass()))) {
      serviceStubsOptionsBuilder.setMetricsScope(metricsScope);
    }
    this.workflowServiceStubs =
            WorkflowServiceStubs.newServiceStubs(serviceStubsOptionsBuilder.build());

    nexusTaskHandler =
        new NexusTaskHandlerImpl(
                WorkflowClient.newInstance(this.workflowServiceStubs),
            testEnvironmentOptions.getWorkflowClientOptions().getNamespace(),
            "test-nexus-env-task-queue",
            testEnvironmentOptions.getWorkflowClientOptions().getDataConverter(),
                testEnvironmentOptions.getWorkerFactoryOptions().getWorkerInterceptors());
  }

  @Override
  public void registerNexusServiceImplementations(Object... implementations) {
    nexusTaskHandler.registerNexusServiceImplementations(implementations);
  }

  @Override
  public <T> T newNexusServiceStub(Class<T> serviceInterface) {
    NexusServiceOptions options =
        NexusServiceOptions.newBuilder()
            .setOperationOptions(
                NexusOperationOptions.newBuilder()
                    .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                    .build())
            .build();
    return newNexusServiceStub(serviceInterface, options);
  }

  @Override
  public <T> T newNexusServiceStub(Class<T> serviceInterface, NexusServiceOptions options) {
    Preconditions.checkState(started.get(), "TestNexusOperationEnvironmentInternal not started");

    ServiceDefinition serviceDef = ServiceDefinition.fromClass(serviceInterface);
    InvocationHandler invocationHandler =
        new NexusServiceInvocationHandler(
            serviceDef, options, new TestNexusExecutor(), (unused) -> {});
    invocationHandler =
        new DeterministicRunnerWrapper(invocationHandler, deterministicRunnerExecutor::submit);
    return (T)
        Proxy.newProxyInstance(
            serviceInterface.getClassLoader(),
            new Class<?>[] {serviceInterface},
            invocationHandler);
  }

  @Override
  public void start() {
    started.set(true);
    nexusTaskHandler.start();
  }

  @Override
  public void close() {}

  private class TestNexusExecutor extends WorkflowOutboundCallsInterceptorBase {

    public TestNexusExecutor() {
      super(null);
    }

    @Override
    public <R> ExecuteNexusOperationOutput<R> executeNexusOperation(
        ExecuteNexusOperationInput<R> input) {
      Optional<Payload> payload =
          testEnvironmentOptions
              .getWorkflowClientOptions()
              .getDataConverter()
              .toPayload(input.getArg());

      NexusOperationOptions options = input.getOptions();
      long timeoutMilli = 10000;
      if (options.getScheduleToCloseTimeout() != null
          && options.getScheduleToCloseTimeout().toMillis() < timeoutMilli) {
        timeoutMilli = options.getScheduleToCloseTimeout().toMillis();
      }

      PollNexusTaskQueueResponse.Builder taskBuilder =
          PollNexusTaskQueueResponse.newBuilder()
              .setTaskToken(ByteString.copyFrom("test-task-token".getBytes(StandardCharsets.UTF_8)))
              .setRequest(
                  Request.newBuilder()
                      .setStartOperation(
                          StartOperationRequest.newBuilder()
                              .setOperation(input.getOperation())
                              .setService(input.getService())
                              .setRequestId(UUID.randomUUID().toString())
                              .setCallback("test-callback")
                              .setPayload(payload.get()))
                      .setScheduledTime(ProtobufTimeUtils.getCurrentProtoTime())
                      .putHeader(Header.REQUEST_TIMEOUT, timeoutMilli + "ms")
                      .build());

      PollNexusTaskQueueResponse task = taskBuilder.build();
      NexusTaskHandler.Result result = executeNexusOperation(task);
      return getReply(task, result, input.getResultClass(), input.getResultType());
    }

    private NexusTaskHandler.Result executeNexusOperation(PollNexusTaskQueueResponse nexusTask) {
      Future<NexusTaskHandler.Result> operationFuture =
          nexusWorkerExecutor.submit(
              () ->
                  nexusTaskHandler.handle(
                      new NexusTask(nexusTask, null, () -> {}),
                      testEnvironmentOptions.getMetricsScope()));

      try {
        return operationFuture.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      } catch (ExecutionException e) {
        log.error("Exception during processing of Nexus task");
        throw new RuntimeException(e);
      }
    }

    private <R> ExecuteNexusOperationOutput<R> getReply(
        PollNexusTaskQueueResponse task,
        NexusTaskHandler.Result taskResult,
        Class<R> resultClass,
        Type resultType) {
      DataConverter dataConverter =
          testEnvironmentOptions.getWorkflowClientOptions().getDataConverter();
      Response response = taskResult.getResponse();
      StartOperationResponse startResponse = response.getStartOperation();
      if (startResponse != null) {
        if (startResponse.hasSyncSuccess()) {
          return new ExecuteNexusOperationOutput(
              Workflow.newPromise(
                  dataConverter.fromPayload(
                      startResponse.getSyncSuccess().getPayload(), resultClass, resultType)),
              Workflow.newPromise(Optional.empty()));
        } else if (startResponse.hasAsyncSuccess()) {
          return new ExecuteNexusOperationOutput(
              Workflow.newPromise(null),
              Workflow.newPromise(Optional.of(startResponse.getAsyncSuccess().getOperationId())));
        } else {
          UnsuccessfulOperationError opError = startResponse.getOperationError();
          throw new NexusOperationFailure(
              "",
              0,
              TEST_ENDPOINT,
              task.getRequest().getStartOperation().getService(),
              task.getRequest().getStartOperation().getOperation(),
              task.getRequest().getStartOperation().getRequestId(),
              new RuntimeException(opError.getFailure().getMessage()));
        }
      } else {
        HandlerError error = taskResult.getHandlerError();
        StartOperationRequest re = task.getRequest().getStartOperation();
        throw new NexusOperationFailure(
            "",
            0,
            TEST_ENDPOINT,
            re.getService(),
            re.getOperation(),
            re.getRequestId(),
            new RuntimeException(error.getFailure().getMessage()));
      }
    }
  }
}
