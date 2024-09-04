package io.temporal.internal.sync;

import io.temporal.workflow.Functions;
import io.temporal.workflow.OperationHandle;
import java.util.concurrent.atomic.AtomicReference;

public class StartNexusCallInternal {

  private static final ThreadLocal<AtomicReference<OperationHandle<?>>> asyncResult =
      new ThreadLocal<>();

  public static boolean isAsync() {
    return asyncResult.get() != null;
  }

  public static <R> void setAsyncResult(OperationHandle<R> handle) {
    AtomicReference<OperationHandle<?>> placeholder = asyncResult.get();
    if (placeholder == null) {
      throw new IllegalStateException("not in invoke invocation");
    }
    placeholder.set(handle);
  }

  /**
   * Indicate to the dynamic interface implementation that call was done through
   *
   * @link Async#invoke}. Must be closed through {@link #closeAsyncInvocation()}
   */
  public static void initAsyncInvocation() {
    if (asyncResult.get() != null) {
      throw new IllegalStateException("already in start invocation");
    }
    asyncResult.set(new AtomicReference<>());
  }

  /**
   * @return asynchronous result of an invocation.
   */
  private static <T> OperationHandle<T> getAsyncInvocationResult() {
    AtomicReference<OperationHandle<?>> reference = asyncResult.get();
    if (reference == null) {
      throw new IllegalStateException("initAsyncInvocation wasn't called");
    }
    @SuppressWarnings("unchecked")
    OperationHandle<T> result = (OperationHandle<T>) reference.get();
    if (result == null) {
      throw new IllegalStateException("start result wasn't set");
    }
    return result;
  }

  /** Closes async invocation created through {@link #initAsyncInvocation()} */
  public static void closeAsyncInvocation() {
    asyncResult.remove();
  }

  public static <T, R> OperationHandle<R> startNexusOperation(Functions.Proc operation) {
    initAsyncInvocation();
    try {
      operation.apply();
      return getAsyncInvocationResult();
    } finally {
      closeAsyncInvocation();
    }
  }
}
