package io.temporal.client;

import static io.temporal.internal.WorkflowThreadMarker.enforceNonWorkflowThread;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.reflect.TypeToken;
import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.TaskReachability;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.WorkflowInvocationHandler.InvocationType;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.internal.WorkflowThreadMarker;
import io.temporal.internal.client.*;
import io.temporal.internal.client.NexusStartWorkflowResponse;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.client.external.GenericWorkflowClientImpl;
import io.temporal.internal.client.external.ManualActivityCompletionClientFactory;
import io.temporal.internal.sync.StubMarker;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

final class WorkflowClientInternalImpl implements WorkflowClient, WorkflowClientInternal {

  private final GenericWorkflowClient genericClient;
  private final WorkflowClientOptions options;
  private final ManualActivityCompletionClientFactory manualActivityCompletionClientFactory;
  private final WorkflowClientCallsInterceptor workflowClientCallsInvoker;
  private final WorkflowServiceStubs workflowServiceStubs;
  private final Scope metricsScope;
  private final WorkflowClientInterceptor[] interceptors;
  private final WorkerFactoryRegistry workerFactoryRegistry = new WorkerFactoryRegistry();

  /**
   * Creates client that connects to an instance of the Temporal Service. Cannot be used from within
   * workflow code.
   *
   * @param service client to the Temporal Service endpoint.
   * @param options Options (like {@link io.temporal.common.converter.DataConverter} override) for
   *     configuring client.
   */
  public static WorkflowClient newInstance(
      WorkflowServiceStubs service, WorkflowClientOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new WorkflowClientInternalImpl(service, options), WorkflowClient.class);
  }

  WorkflowClientInternalImpl(
      WorkflowServiceStubs workflowServiceStubs, WorkflowClientOptions options) {
    options = WorkflowClientOptions.newBuilder(options).validateAndBuildWithDefaults();
    workflowServiceStubs =
        new NamespaceInjectWorkflowServiceStubs(workflowServiceStubs, options.getNamespace());
    this.options = options;
    this.workflowServiceStubs = workflowServiceStubs;
    this.metricsScope =
        workflowServiceStubs
            .getOptions()
            .getMetricsScope()
            .tagged(MetricsTag.defaultTags(options.getNamespace()));
    this.genericClient = new GenericWorkflowClientImpl(workflowServiceStubs, metricsScope);
    this.interceptors = options.getInterceptors();
    this.workflowClientCallsInvoker = initializeClientInvoker();
    this.manualActivityCompletionClientFactory =
        ManualActivityCompletionClientFactory.newFactory(
            workflowServiceStubs,
            options.getNamespace(),
            options.getIdentity(),
            options.getDataConverter());
  }

  private WorkflowClientCallsInterceptor initializeClientInvoker() {
    WorkflowClientCallsInterceptor workflowClientInvoker =
        new RootWorkflowClientInvoker(genericClient, options, workerFactoryRegistry);
    for (WorkflowClientInterceptor clientInterceptor : interceptors) {
      workflowClientInvoker =
          clientInterceptor.workflowClientCallsInterceptor(workflowClientInvoker);
    }
    return workflowClientInvoker;
  }

  @Override
  public WorkflowServiceStubs getWorkflowServiceStubs() {
    return workflowServiceStubs;
  }

  @Override
  public WorkflowClientOptions getOptions() {
    return options;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T newWorkflowStub(Class<T> workflowInterface, WorkflowOptions options) {
    checkAnnotation(workflowInterface, WorkflowMethod.class);
    WorkflowInvocationHandler invocationHandler =
        new WorkflowInvocationHandler(
            workflowInterface, this.getOptions(), workflowClientCallsInvoker, options);
    return (T)
        Proxy.newProxyInstance(
            workflowInterface.getClassLoader(),
            new Class<?>[] {workflowInterface, StubMarker.class},
            invocationHandler);
  }

  @SafeVarargs
  private static <T> void checkAnnotation(
      Class<T> workflowInterface, Class<? extends Annotation>... annotationClasses) {
    TypeToken<?>.TypeSet interfaces = TypeToken.of(workflowInterface).getTypes().interfaces();
    if (interfaces.isEmpty()) {
      throw new IllegalArgumentException("Workflow must implement at least one interface");
    }
    for (TypeToken<?> i : interfaces) {
      for (Method method : i.getRawType().getMethods()) {
        for (Class<? extends Annotation> annotationClass : annotationClasses) {
          Object workflowMethod = method.getAnnotation(annotationClass);
          if (workflowMethod != null) {
            return;
          }
        }
      }
    }
    throw new IllegalArgumentException(
        "Workflow interface "
            + workflowInterface.getName()
            + " doesn't have method annotated with any of "
            + Arrays.toString(annotationClasses));
  }

  @Override
  public <T> T newWorkflowStub(Class<T> workflowInterface, String workflowId) {
    return newWorkflowStub(workflowInterface, workflowId, Optional.empty());
  }

  @Override
  public <T> T newWorkflowStub(
      Class<T> workflowInterface, String workflowId, Optional<String> runId) {
    checkAnnotation(
        workflowInterface,
        WorkflowMethod.class,
        QueryMethod.class,
        SignalMethod.class,
        UpdateMethod.class);
    if (Strings.isNullOrEmpty(workflowId)) {
      throw new IllegalArgumentException("workflowId is null or empty");
    }
    WorkflowExecution execution =
        WorkflowExecution.newBuilder().setWorkflowId(workflowId).setRunId(runId.orElse("")).build();

    WorkflowInvocationHandler invocationHandler =
        new WorkflowInvocationHandler(
            workflowInterface, this.getOptions(), workflowClientCallsInvoker, execution);
    @SuppressWarnings("unchecked")
    T result =
        (T)
            Proxy.newProxyInstance(
                workflowInterface.getClassLoader(),
                new Class<?>[] {workflowInterface, StubMarker.class},
                invocationHandler);
    return result;
  }

  @Override
  public WorkflowStub newUntypedWorkflowStub(String workflowId) {
    return newUntypedWorkflowStub(workflowId, Optional.empty(), Optional.empty());
  }

  @Override
  @SuppressWarnings("deprecation")
  public WorkflowStub newUntypedWorkflowStub(String workflowType, WorkflowOptions workflowOptions) {
    WorkflowStub result =
        new WorkflowStubImpl(options, workflowClientCallsInvoker, workflowType, workflowOptions);
    for (WorkflowClientInterceptor i : interceptors) {
      result = i.newUntypedWorkflowStub(workflowType, workflowOptions, result);
    }
    return result;
  }

  @Override
  public WorkflowStub newUntypedWorkflowStub(
      String workflowId, Optional<String> runId, Optional<String> workflowType) {
    WorkflowExecution execution =
        WorkflowExecution.newBuilder().setWorkflowId(workflowId).setRunId(runId.orElse("")).build();
    return newUntypedWorkflowStub(execution, workflowType);
  }

  @Override
  @SuppressWarnings("deprecation")
  public WorkflowStub newUntypedWorkflowStub(
      WorkflowExecution execution, Optional<String> workflowType) {
    WorkflowStub result =
        new WorkflowStubImpl(options, workflowClientCallsInvoker, workflowType, execution);
    for (WorkflowClientInterceptor i : interceptors) {
      result = i.newUntypedWorkflowStub(execution, workflowType, result);
    }
    return result;
  }

  @Override
  public ActivityCompletionClient newActivityCompletionClient() {
    ActivityCompletionClient result =
        WorkflowThreadMarker.protectFromWorkflowThread(
            new ActivityCompletionClientImpl(
                manualActivityCompletionClientFactory, () -> {}, metricsScope, null),
            ActivityCompletionClient.class);
    for (WorkflowClientInterceptor i : interceptors) {
      result = i.newActivityCompletionClient(result);
    }
    return result;
  }

  @Override
  public BatchRequest newSignalWithStartRequest() {
    return new SignalWithStartBatchRequest();
  }

  @Override
  public WorkflowExecution signalWithStart(BatchRequest signalWithStartBatch) {
    return ((SignalWithStartBatchRequest) signalWithStartBatch).invoke();
  }

  @Override
  public Stream<WorkflowExecutionMetadata> listExecutions(@Nullable String query) {
    return listExecutions(query, null);
  }

  @Override
  public WorkflowExecutionCount countWorkflows(@Nullable String query) {
    WorkflowClientCallsInterceptor.CountWorkflowsInput input =
        new WorkflowClientCallsInterceptor.CountWorkflowsInput(query);
    return workflowClientCallsInvoker.countWorkflows(input).getCount();
  }

  Stream<WorkflowExecutionMetadata> listExecutions(
      @Nullable String query, @Nullable Integer pageSize) {
    return workflowClientCallsInvoker
        .listWorkflowExecutions(
            new WorkflowClientCallsInterceptor.ListWorkflowExecutionsInput(query, pageSize))
        .getStream();
  }

  @Override
  public Stream<HistoryEvent> streamHistory(@Nonnull String workflowId) {
    return streamHistory(workflowId, null);
  }

  @Override
  public Stream<HistoryEvent> streamHistory(@Nonnull String workflowId, @Nullable String runId) {
    Preconditions.checkNotNull(workflowId, "workflowId is required");

    WorkflowExecution.Builder executionBuilder =
        WorkflowExecution.newBuilder().setWorkflowId(workflowId);
    if (runId != null) {
      executionBuilder.setRunId(runId);
    }
    WorkflowExecution execution = executionBuilder.build();

    return streamHistory(execution);
  }

  @Override
  public WorkflowExecutionHistory fetchHistory(@Nonnull String workflowId) {
    return fetchHistory(workflowId, null);
  }

  @Override
  public WorkflowExecutionHistory fetchHistory(@Nonnull String workflowId, @Nullable String runId) {
    Preconditions.checkNotNull(workflowId, "execution is required");

    return new WorkflowExecutionHistory(
        History.newBuilder()
            .addAllEvents(streamHistory(workflowId, runId).collect(Collectors.toList()))
            .build(),
        workflowId);
  }

  @Override
  public void updateWorkerBuildIdCompatability(
      @Nonnull String taskQueue, @Nonnull BuildIdOperation operation) {
    UpdateWorkerBuildIdCompatibilityRequest.Builder reqBuilder =
        UpdateWorkerBuildIdCompatibilityRequest.newBuilder()
            .setTaskQueue(taskQueue)
            .setNamespace(options.getNamespace());
    operation.augmentBuilder(reqBuilder);
    genericClient.updateWorkerBuildIdCompatability(reqBuilder.build());
  }

  @Override
  public WorkerBuildIdVersionSets getWorkerBuildIdCompatability(@Nonnull String taskQueue) {
    GetWorkerBuildIdCompatibilityRequest req =
        GetWorkerBuildIdCompatibilityRequest.newBuilder()
            .setTaskQueue(taskQueue)
            .setNamespace(options.getNamespace())
            .build();
    GetWorkerBuildIdCompatibilityResponse resp = genericClient.getWorkerBuildIdCompatability(req);
    return new WorkerBuildIdVersionSets(resp);
  }

  @Override
  public WorkerTaskReachability getWorkerTaskReachability(
      @Nonnull Iterable<String> buildIds,
      @Nonnull Iterable<String> taskQueues,
      TaskReachability reachability) {
    GetWorkerTaskReachabilityRequest req =
        GetWorkerTaskReachabilityRequest.newBuilder()
            .setNamespace(options.getNamespace())
            .addAllBuildIds(buildIds)
            .addAllTaskQueues(taskQueues)
            .setReachability(reachability)
            .build();
    GetWorkerTaskReachabilityResponse resp = genericClient.GetWorkerTaskReachability(req);
    return new WorkerTaskReachability(resp);
  }

  public static WorkflowExecution start(Functions.Proc workflow) {
    enforceNonWorkflowThread();
    WorkflowInvocationHandler.initAsyncInvocation(InvocationType.START);
    try {
      workflow.apply();
      return WorkflowInvocationHandler.getAsyncInvocationResult(WorkflowExecution.class);
    } finally {
      WorkflowInvocationHandler.closeAsyncInvocation();
    }
  }

  public static <A1> WorkflowExecution start(Functions.Proc1<A1> workflow, A1 arg1) {
    return start(() -> workflow.apply(arg1));
  }

  public static <A1, A2> WorkflowExecution start(
      Functions.Proc2<A1, A2> workflow, A1 arg1, A2 arg2) {
    return start(() -> workflow.apply(arg1, arg2));
  }

  public static <A1, A2, A3> WorkflowExecution start(
      Functions.Proc3<A1, A2, A3> workflow, A1 arg1, A2 arg2, A3 arg3) {
    return start(() -> workflow.apply(arg1, arg2, arg3));
  }

  public static <A1, A2, A3, A4> WorkflowExecution start(
      Functions.Proc4<A1, A2, A3, A4> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return start(() -> workflow.apply(arg1, arg2, arg3, arg4));
  }

  public static <A1, A2, A3, A4, A5> WorkflowExecution start(
      Functions.Proc5<A1, A2, A3, A4, A5> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
    return start(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5));
  }

  public static <A1, A2, A3, A4, A5, A6> WorkflowExecution start(
      Functions.Proc6<A1, A2, A3, A4, A5, A6> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return start(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5, arg6));
  }

  public static <R> WorkflowExecution start(Functions.Func<R> workflow) {
    return start((Functions.Proc) workflow::apply);
  }

  public static <A1, R> WorkflowExecution start(Functions.Func1<A1, R> workflow, A1 arg1) {
    return start(() -> workflow.apply(arg1));
  }

  public static <A1, A2, R> WorkflowExecution start(
      Functions.Func2<A1, A2, R> workflow, A1 arg1, A2 arg2) {
    return start(() -> workflow.apply(arg1, arg2));
  }

  public static <A1, A2, A3, R> WorkflowExecution start(
      Functions.Func3<A1, A2, A3, R> workflow, A1 arg1, A2 arg2, A3 arg3) {
    return start(() -> workflow.apply(arg1, arg2, arg3));
  }

  public static <A1, A2, A3, A4, R> WorkflowExecution start(
      Functions.Func4<A1, A2, A3, A4, R> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return start(() -> workflow.apply(arg1, arg2, arg3, arg4));
  }

  public static <A1, A2, A3, A4, A5, R> WorkflowExecution start(
      Functions.Func5<A1, A2, A3, A4, A5, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    return start(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5));
  }

  public static <A1, A2, A3, A4, A5, A6, R> WorkflowExecution start(
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return start(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5, arg6));
  }

  @SuppressWarnings("unchecked")
  public static CompletableFuture<Void> execute(Functions.Proc workflow) {
    enforceNonWorkflowThread();
    WorkflowInvocationHandler.initAsyncInvocation(InvocationType.EXECUTE);
    try {
      workflow.apply();
      return WorkflowInvocationHandler.getAsyncInvocationResult(CompletableFuture.class);
    } finally {
      WorkflowInvocationHandler.closeAsyncInvocation();
    }
  }

  public static <A1> CompletableFuture<Void> execute(Functions.Proc1<A1> workflow, A1 arg1) {
    return execute(() -> workflow.apply(arg1));
  }

  public static <A1, A2> CompletableFuture<Void> execute(
      Functions.Proc2<A1, A2> workflow, A1 arg1, A2 arg2) {
    return execute(() -> workflow.apply(arg1, arg2));
  }

  public static <A1, A2, A3> CompletableFuture<Void> execute(
      Functions.Proc3<A1, A2, A3> workflow, A1 arg1, A2 arg2, A3 arg3) {
    return execute(() -> workflow.apply(arg1, arg2, arg3));
  }

  public static <A1, A2, A3, A4> CompletableFuture<Void> execute(
      Functions.Proc4<A1, A2, A3, A4> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return execute(() -> workflow.apply(arg1, arg2, arg3, arg4));
  }

  public static <A1, A2, A3, A4, A5> CompletableFuture<Void> execute(
      Functions.Proc5<A1, A2, A3, A4, A5> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
    return execute(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5));
  }

  public static <A1, A2, A3, A4, A5, A6> CompletableFuture<Void> execute(
      Functions.Proc6<A1, A2, A3, A4, A5, A6> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return execute(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5, arg6));
  }

  @SuppressWarnings("unchecked")
  public static <R> CompletableFuture<R> execute(Functions.Func<R> workflow) {
    return (CompletableFuture<R>) execute((Functions.Proc) workflow::apply);
  }

  public static <A1, R> CompletableFuture<R> execute(Functions.Func1<A1, R> workflow, A1 arg1) {
    return execute(() -> workflow.apply(arg1));
  }

  public static <A1, A2, R> CompletableFuture<R> execute(
      Functions.Func2<A1, A2, R> workflow, A1 arg1, A2 arg2) {
    return execute(() -> workflow.apply(arg1, arg2));
  }

  public static <A1, A2, A3, R> CompletableFuture<R> execute(
      Functions.Func3<A1, A2, A3, R> workflow, A1 arg1, A2 arg2, A3 arg3) {
    return execute(() -> workflow.apply(arg1, arg2, arg3));
  }

  public static <A1, A2, A3, A4, R> CompletableFuture<R> execute(
      Functions.Func4<A1, A2, A3, A4, R> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return execute(() -> workflow.apply(arg1, arg2, arg3, arg4));
  }

  public static <A1, A2, A3, A4, A5, R> CompletableFuture<R> execute(
      Functions.Func5<A1, A2, A3, A4, A5, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    return execute(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5));
  }

  public static <A1, A2, A3, A4, A5, A6, R> CompletableFuture<R> execute(
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return execute(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5, arg6));
  }

  public static WorkflowUpdateHandle<Void> startUpdate(
      Functions.Proc updateMethod, UpdateOptions<?> options) {
    enforceNonWorkflowThread();
    WorkflowInvocationHandler.initAsyncInvocation(InvocationType.UPDATE, options);
    try {
      updateMethod.apply();
      return WorkflowInvocationHandler.getAsyncInvocationResult(WorkflowUpdateHandle.class);
    } finally {
      WorkflowInvocationHandler.closeAsyncInvocation();
    }
  }

  public static <A1> WorkflowUpdateHandle<Void> startUpdate(
      Functions.Proc1<A1> updateMethod, A1 arg1, UpdateOptions<Void> options) {
    return startUpdate(() -> updateMethod.apply(arg1), options);
  }

  public static <A1, A2> WorkflowUpdateHandle<Void> startUpdate(
      Functions.Proc2<A1, A2> updateMethod, A1 arg1, A2 arg2, UpdateOptions<Void> options) {
    return startUpdate(() -> updateMethod.apply(arg1, arg2), options);
  }

  public static <A1, A2, A3> WorkflowUpdateHandle<Void> startUpdate(
      Functions.Proc3<A1, A2, A3> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      UpdateOptions<Void> options) {
    return startUpdate(() -> updateMethod.apply(arg1, arg2, arg3), options);
  }

  public static <A1, A2, A3, A4> WorkflowUpdateHandle<Void> startUpdate(
      Functions.Proc4<A1, A2, A3, A4> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      UpdateOptions<Void> options) {
    return startUpdate(() -> updateMethod.apply(arg1, arg2, arg3, arg4), options);
  }

  public static <A1, A2, A3, A4, A5> WorkflowUpdateHandle<Void> startUpdate(
      Functions.Proc5<A1, A2, A3, A4, A5> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      UpdateOptions<Void> options) {
    return startUpdate(() -> updateMethod.apply(arg1, arg2, arg3, arg4, arg5), options);
  }

  public static <A1, A2, A3, A4, A5, A6> WorkflowUpdateHandle<Void> startUpdate(
      Functions.Proc6<A1, A2, A3, A4, A5, A6> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      UpdateOptions<Void> options) {
    return startUpdate(() -> updateMethod.apply(arg1, arg2, arg3, arg4, arg5, arg6), options);
  }

  public static <R> WorkflowUpdateHandle<R> startUpdate(
      Functions.Func<R> updateMethod, UpdateOptions<R> options) {
    return (WorkflowUpdateHandle<R>) startUpdate((Functions.Proc) updateMethod::apply, options);
  }

  public static <A1, R> WorkflowUpdateHandle<R> startUpdate(
      Functions.Func1<A1, R> updateMethod, A1 arg1, UpdateOptions<R> options) {
    return startUpdate(() -> updateMethod.apply(arg1), options);
  }

  public static <A1, A2, R> WorkflowUpdateHandle<R> startUpdate(
      Functions.Func2<A1, A2, R> updateMethod, A1 arg1, A2 arg2, UpdateOptions<R> options) {
    return startUpdate(() -> updateMethod.apply(arg1, arg2), options);
  }

  public static <A1, A2, A3, R> WorkflowUpdateHandle<R> startUpdate(
      Functions.Func3<A1, A2, A3, R> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      UpdateOptions<R> options) {
    return startUpdate(() -> updateMethod.apply(arg1, arg2, arg3), options);
  }

  public static <A1, A2, A3, A4, R> WorkflowUpdateHandle<R> startUpdate(
      Functions.Func4<A1, A2, A3, A4, R> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      UpdateOptions<R> options) {
    return startUpdate(() -> updateMethod.apply(arg1, arg2, arg3, arg4), options);
  }

  public static <A1, A2, A3, A4, A5, R> WorkflowUpdateHandle<R> startUpdate(
      Functions.Func5<A1, A2, A3, A4, A5, R> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      UpdateOptions<R> options) {
    return startUpdate(() -> updateMethod.apply(arg1, arg2, arg3, arg4, arg5), options);
  }

  public static <A1, A2, A3, A4, A5, A6, R> WorkflowUpdateHandle<R> startUpdate(
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      UpdateOptions<R> options) {
    return startUpdate(() -> updateMethod.apply(arg1, arg2, arg3, arg4, arg5, arg6), options);
  }

  public static <R> WorkflowUpdateHandle<R> startUpdateWithStart(
      Functions.Proc updateMethod,
      UpdateOptions<R> updateOptions,
      WithStartWorkflowOperation<?> startOp) {
    enforceNonWorkflowThread();
    WorkflowInvocationHandler.initAsyncInvocation(
        InvocationType.UPDATE_WITH_START,
        new WorkflowInvocationHandler.UpdateWithStartOptions(updateOptions, startOp));
    try {
      updateMethod.apply();

      if (startOp.getStartMethod() != null) { // only present when using typed API
        startOp.getStartMethod().apply();
      }

      return WorkflowInvocationHandler.getAsyncInvocationResult(WorkflowUpdateHandle.class);
    } finally {
      WorkflowInvocationHandler.closeAsyncInvocation();
    }
  }

  public static <R> R executeUpdateWithStart(
      Functions.Proc updateMethod,
      UpdateOptions<R> updateOptions,
      WithStartWorkflowOperation<?> startOp) {
    updateOptions.validateWaitForCompleted();
    UpdateOptions<R> optionsWithWaitStageCompleted =
        updateOptions.toBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build();
    return startUpdateWithStart(updateMethod, optionsWithWaitStageCompleted, startOp).getResult();
  }

  Stream<HistoryEvent> streamHistory(WorkflowExecution execution) {
    Preconditions.checkNotNull(execution, "execution is required");

    GetWorkflowExecutionHistoryIterator iterator =
        new GetWorkflowExecutionHistoryIterator(
            options.getNamespace(), execution, null, genericClient);
    iterator.init();

    // IMMUTABLE here means that "interference" (in Java Streams terms) to this spliterator is
    // impossible
    final int CHARACTERISTICS =
        Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.DISTINCT | Spliterator.IMMUTABLE;

    return StreamSupport.stream(
        Spliterators.spliteratorUnknownSize(iterator, CHARACTERISTICS), false);
  }

  @Override
  public Object getInternal() {
    return this;
  }

  @Override
  public void registerWorkerFactory(WorkerFactory workerFactory) {
    workerFactoryRegistry.register(workerFactory);
  }

  @Override
  public void deregisterWorkerFactory(WorkerFactory workerFactory) {
    workerFactoryRegistry.deregister(workerFactory);
  }

  @Override
  public NexusStartWorkflowResponse startNexus(
      NexusStartWorkflowRequest request, Functions.Proc workflow) {
    enforceNonWorkflowThread();
    WorkflowInvocationHandler.initAsyncInvocation(InvocationType.START_NEXUS, request);
    try {
      workflow.apply();
      return WorkflowInvocationHandler.getAsyncInvocationResult(NexusStartWorkflowResponse.class);
    } finally {
      WorkflowInvocationHandler.closeAsyncInvocation();
    }
  }
}
