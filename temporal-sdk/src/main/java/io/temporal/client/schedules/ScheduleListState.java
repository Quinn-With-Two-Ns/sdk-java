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

import java.util.Objects;

/** State of a listed schedule. */
public class ScheduleListState {
  private final String note;
  private final boolean paused;

  public ScheduleListState(String note, boolean paused) {
    this.note = note;
    this.paused = paused;
  }

  /** Human-readable message for the schedule. */
  public String getNote() {
    return note;
  }

  /** Whether the schedule is paused */
  public boolean isPaused() {
    return paused;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleListState that = (ScheduleListState) o;
    return paused == that.paused && Objects.equals(note, that.note);
  }

  @Override
  public int hashCode() {
    return Objects.hash(note, paused);
  }

  @Override
  public String toString() {
    return "ScheduleListState{" + "note='" + note + '\'' + ", paused=" + paused + '}';
  }
}
