package io.temporal.internal.worker;

import io.temporal.internal.nexus.NexusTaskHandlerImpl;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.tuning.NexusSlotInfo;
import io.temporal.worker.tuning.SlotSupplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SyncNexusWorker implements SuspendableWorker {
  private static final Logger log = LoggerFactory.getLogger(SyncNexusWorker.class);

  private final String identity;
  private final String namespace;
  private final String taskQueue;
  private final NexusTaskHandlerImpl taskHandler;
  private final NexusWorker worker;

  public SyncNexusWorker(
      WorkflowServiceStubs service,
      String namespace,
      String taskQueue,
      SingleWorkerOptions options,
      SlotSupplier<NexusSlotInfo> slotSupplier) {
    this.identity = options.getIdentity();
    this.namespace = namespace;
    this.taskQueue = taskQueue;

    this.taskHandler = new NexusTaskHandlerImpl(namespace, taskQueue, options.getDataConverter());
    this.worker =
        new NexusWorker(service, namespace, taskQueue, options, taskHandler, slotSupplier);
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    return worker
        .shutdown(shutdownManager, interruptTasks)
        .exceptionally(
            e -> {
              log.error("[BUG] Unexpected exception during shutdown", e);
              return null;
            });
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    long timeoutMillis = unit.toMillis(timeout);
    ShutdownManager.awaitTermination(worker, timeoutMillis);
  }

  @Override
  public boolean start() {
    return worker.start();
  }

  @Override
  public void suspendPolling() {
    worker.suspendPolling();
  }

  @Override
  public void resumePolling() {
    worker.resumePolling();
  }

  @Override
  public boolean isSuspended() {
    return worker.isSuspended();
  }

  @Override
  public boolean isShutdown() {
    return worker.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return worker.isTerminated();
  }

  @Override
  public WorkerLifecycleState getLifecycleState() {
    return worker.getLifecycleState();
  }

  @Override
  public String toString() {
    return String.format(
        "SyncNexusWorker{namespace=%s, taskQueue=%s, identity=%s}", namespace, taskQueue, identity);
  }

  public void registerNexusServiceImplementation(Object... nexusServiceImplementations) {
    taskHandler.registerNexusServiceImplementations(nexusServiceImplementations);
  }
}
