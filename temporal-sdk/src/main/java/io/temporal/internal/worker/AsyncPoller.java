package io.temporal.internal.worker;

import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.internal.BackoffThrottler;
import io.temporal.worker.MetricsType;
import io.temporal.worker.tuning.PollerBehaviorAutoscaling;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.worker.tuning.SlotReleaseReason;
import io.temporal.worker.tuning.SlotSupplierFuture;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AsyncPoller is a poller that uses a single thread per async task poller. It also supports
 * autoscaling the number of pollers based on the feedback from the poll tasks.
 */
final class AsyncPoller<T extends ScalingTask> extends BasePoller<T> {
  private static final Logger log = LoggerFactory.getLogger(AsyncPoller.class);
  private final TrackingSlotSupplier<?> slotSupplier;
  private final SlotReservationData slotReservationData;
  private final List<PollTaskAsync<T>> asyncTaskPollers;
  private final PollerOptions pollerOptions;
  private final PollerBehaviorAutoscaling pollerBehavior;
  private final Scope workerMetricsScope;
  private Throttler pollRateThrottler;
  private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler =
      new PollerUncaughtExceptionHandler();

  AsyncPoller(
      TrackingSlotSupplier<?> slotSupplier,
      SlotReservationData slotReservationData,
      PollTaskAsync<T> asyncTaskPoller,
      ShutdownableTaskExecutor<T> pollTaskExecutor,
      PollerOptions pollerOptions,
      Scope workerMetricsScope) {
    this(
        slotSupplier,
        slotReservationData,
        Collections.singletonList(asyncTaskPoller),
        pollTaskExecutor,
        pollerOptions,
        workerMetricsScope);
  }

  AsyncPoller(
      TrackingSlotSupplier<?> slotSupplier,
      SlotReservationData slotReservationData,
      List<PollTaskAsync<T>> asyncTaskPollers,
      ShutdownableTaskExecutor<T> pollTaskExecutor,
      PollerOptions pollerOptions,
      Scope workerMetricsScope) {
    super(pollTaskExecutor);
    Objects.requireNonNull(slotSupplier, "slot supplier cannot be null");
    Objects.requireNonNull(slotReservationData, "slot reservation data should not be null");
    Objects.requireNonNull(asyncTaskPollers, "async tasK pollers should not be null");
    if (asyncTaskPollers.isEmpty()) {
      throw new IllegalArgumentException("async task pollers must contain at least one poller");
    }
    Objects.requireNonNull(pollerOptions, "pollerOptions should not be null");
    Objects.requireNonNull(workerMetricsScope, "workerMetricsScope should not be null");
    this.slotSupplier = slotSupplier;
    this.slotReservationData = slotReservationData;
    this.asyncTaskPollers = asyncTaskPollers;
    if (!(pollerOptions.getPollerBehavior() instanceof PollerBehaviorAutoscaling)) {
      throw new IllegalArgumentException(
          "PollerBehavior "
              + pollerOptions.getPollerBehavior()
              + " is not supported. Only PollerBehaviorSimpleMaximum is supported.");
    }
    this.pollerBehavior = (PollerBehaviorAutoscaling) pollerOptions.getPollerBehavior();
    this.pollerOptions = pollerOptions;
    this.workerMetricsScope = workerMetricsScope;
  }

  @Override
  public boolean start() {
    log.info("Starting async poller!!!!");
    if (pollerOptions.getMaximumPollRatePerSecond() > 0.0) {
      pollRateThrottler =
          new Throttler(
              "poller",
              pollerOptions.getMaximumPollRatePerSecond(),
              pollerOptions.getMaximumPollRateIntervalMilliseconds());
    }
    // Each poller will have its own thread and one thread will be used to schedule the scale
    // reporters
    ScheduledExecutorService exec =
        Executors.newScheduledThreadPool(
            asyncTaskPollers.size() + 1,
            new ExecutorThreadFactory(
                pollerOptions.getPollThreadNamePrefix(),
                pollerOptions.getUncaughtExceptionHandler()));
    for (PollTaskAsync<T> asyncTaskPoller : asyncTaskPollers) {
      AdjustableSemaphore pollerSemaphore = new AdjustableSemaphore();
      pollerSemaphore.setMaxPermits(pollerBehavior.getInitialMaxConcurrentTaskPollers());
      PollScaleReportHandle pollScaleReportHandle =
          new PollScaleReportHandle<>(
              pollerBehavior.getMinConcurrentTaskPollers(),
              pollerBehavior.getMaxConcurrentTaskPollers(),
              pollerBehavior.getInitialMaxConcurrentTaskPollers(),
              (newTarget) -> {
                log.info(
                    "Updating maximum number of pollers to: {} for {}", newTarget, asyncTaskPoller);
                pollerSemaphore.setMaxPermits(newTarget);
              });
      exec.execute(new PollQueueTask(asyncTaskPoller, pollerSemaphore, pollScaleReportHandle));
      exec.scheduleAtFixedRate(pollScaleReportHandle, 0, 100, TimeUnit.MILLISECONDS);
    }
    pollExecutor = exec;
    return true;
  }

  public interface PollTaskAsync<TT> {

    CompletableFuture<TT> poll(SlotPermit permit);
  }

  private class PollQueueTask implements Runnable {
    private final PollTaskAsync<T> asyncTaskPoller;
    private final PollScaleReportHandle<T> pollScaleReportHandle;
    private final AdjustableSemaphore pollerSemaphore;

    private final BackoffThrottler pollBackoffThrottler;

    PollQueueTask(
        PollTaskAsync<T> asyncTaskPoller,
        AdjustableSemaphore pollerSemaphore,
        PollScaleReportHandle<T> pollScaleReportHandle) {
      this.asyncTaskPoller = asyncTaskPoller;
      this.pollBackoffThrottler =
          new BackoffThrottler(
              pollerOptions.getBackoffInitialInterval(),
              pollerOptions.getBackoffCongestionInitialInterval(),
              pollerOptions.getBackoffMaximumInterval(),
              pollerOptions.getBackoffCoefficient(),
              pollerOptions.getBackoffMaximumJitterCoefficient());
      this.pollerSemaphore = pollerSemaphore;
      this.pollScaleReportHandle = pollScaleReportHandle;
    }

    @Override
    public void run() {
      try {
        long throttleMs = pollBackoffThrottler.getSleepTime();
        if (throttleMs > 0) {
          Thread.sleep(throttleMs);
        }
        if (pollRateThrottler != null) {
          pollRateThrottler.throttle();
        }

        CountDownLatch suspender = suspendLatch.get();
        if (suspender != null) {
          if (log.isDebugEnabled()) {
            log.debug("poll task suspending latchCount=" + suspender.getCount());
          }
          suspender.await();
        }

        if (shouldTerminate()) {
          return;
        }

        SlotPermit permit;
        SlotSupplierFuture future;
        try {
          future = slotSupplier.reserveSlot(slotReservationData);
        } catch (Exception e) {
          log.warn("Error while trying to reserve a slot", e.getCause());
          return;
        }
        permit = MultiThreadedPoller.getSlotPermitAndHandleInterrupts(future, slotSupplier);
        if (permit == null) return;

        //
        pollerSemaphore.acquire();
        workerMetricsScope.counter(MetricsType.POLLER_START_COUNTER).inc(1);

        asyncTaskPoller
            .poll(permit)
            .handle(
                (task, e) -> {
                  pollerSemaphore.release();
                  pollScaleReportHandle.report(task, e);
                  if (e != null) {
                    log.warn("Error while polling task", e);
                    pollBackoffThrottler.failure(
                        (e instanceof StatusRuntimeException)
                            ? ((StatusRuntimeException) e).getStatus().getCode()
                            : Status.Code.UNKNOWN);
                    slotSupplier.releaseSlot(SlotReleaseReason.neverUsed(), permit);
                    return null;
                  }
                  log.trace("$$$$$ Picked up task: {}", task);
                  if (task != null) {
                    taskExecutor.process(task);
                  } else {
                    slotSupplier.releaseSlot(SlotReleaseReason.neverUsed(), permit);
                  }
                  pollBackoffThrottler.success();
                  return null;
                });
      } catch (Throwable e) {
        if (e instanceof InterruptedException) {
          // we restore the flag here, so it can be checked and processed (with exit) in finally.
          Thread.currentThread().interrupt();
        }
        uncaughtExceptionHandler.uncaughtException(Thread.currentThread(), e);
      } finally {
        if (!shouldTerminate()) {
          // Resubmit itself back to pollExecutor
          pollExecutor.execute(this);
        } else {
          log.info(
              "poll loop is terminated: {}",
              AsyncPoller.this.asyncTaskPollers.getClass().getSimpleName());
        }
      }
    }
  }
}
