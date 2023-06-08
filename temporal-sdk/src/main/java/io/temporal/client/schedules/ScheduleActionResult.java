/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.client.schedules;

import java.time.Instant;
import java.util.Objects;

/** Information about when an action took place. */
public final class ScheduleActionResult {
  private final Instant scheduledAt;
  private final Instant startedAt;
  private final ScheduleActionExecution action;

  public ScheduleActionResult(
      Instant scheduledAt, Instant startedAt, ScheduleActionExecution action) {
    this.scheduledAt = scheduledAt;
    this.startedAt = startedAt;
    this.action = action;
  }

  /**
   * Get the scheduled time of the action including jitter.
   *
   * @return scheduled time of action
   */
  public Instant getScheduledAt() {
    return scheduledAt;
  }

  /**
   * Get when the action actually started.
   *
   * @return time action actually started
   */
  public Instant getStartedAt() {
    return startedAt;
  }

  /**
   * Action that took place.
   *
   * @return action started
   */
  public ScheduleActionExecution getAction() {
    return action;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleActionResult that = (ScheduleActionResult) o;
    return Objects.equals(scheduledAt, that.scheduledAt)
        && Objects.equals(startedAt, that.startedAt)
        && Objects.equals(action, that.action);
  }

  @Override
  public int hashCode() {
    return Objects.hash(scheduledAt, startedAt, action);
  }

  @Override
  public String toString() {
    return "ScheduleActionResult{"
        + "scheduledAt="
        + scheduledAt
        + ", startedAt="
        + startedAt
        + ", action="
        + action
        + '}';
  }
}
