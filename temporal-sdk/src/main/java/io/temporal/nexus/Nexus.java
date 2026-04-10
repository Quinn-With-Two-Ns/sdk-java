package io.temporal.nexus;

import io.temporal.internal.nexus.NexusInternal;
import io.temporal.internal.sync.WorkflowInternal;

/** This class contains methods exposing Temporal APIs for Nexus Operations */
public final class Nexus {
  /**
   * Can be used to get information about a Nexus Operation. This static method relies on a
   * thread-local variable and works only in the original Nexus thread.
   */
  public static NexusOperationContext getOperationContext() {
    return NexusInternal.getOperationContext();
  }

  /**
   * Returns true if the current thread is executing inside a Nexus operation handler. Useful for
   * context-aware components (such as codecs or interceptors) that need to behave differently
   * inside vs outside a Nexus handler.
   */
  public static boolean isInOperationHandler() {
    return NexusInternal.isInOperationHandler();
  }

  /**
   * Use this to rethrow a checked exception from a Nexus Operation instead of adding the exception
   * to a method signature.
   *
   * @return Never returns; always throws. Throws original exception if e is {@link
   *     RuntimeException} or {@link Error}.
   */
  public static RuntimeException wrap(Throwable e) {
    return WorkflowInternal.wrap(e);
  }

  /** Prohibits instantiation. */
  private Nexus() {}
}
