package io.temporal.worker.tuning;

import io.temporal.common.Experimental;

/**
 * Defines the behavior of a poller.
 *
 * <p>Users are not expected to implement this interface directly. Instead, they should use the
 * provided implementations like {@link PollerBehaviorAutoscaling} or {@link
 * PollerBehaviorSimpleMaximum}. For all intents and purpose this interface should be considered
 * sealed.
 */
@Experimental
public interface PollerBehavior {}
