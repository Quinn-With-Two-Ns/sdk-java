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

package io.temporal.client;

import io.temporal.api.enums.v1.TaskReachability;
import io.temporal.api.workflowservice.v1.GetWorkerTaskReachabilityResponse;
import io.temporal.common.Experimental;
import java.util.*;

/** Contains information about the reachability of some Build IDs. */
@Experimental
public class WorkerTaskReachability {
  private final Map<String, BuildIdReachability> buildIdReachability;

  public WorkerTaskReachability(GetWorkerTaskReachabilityResponse reachabilityResponse) {
    buildIdReachability = new HashMap<>(reachabilityResponse.getBuildIdReachabilityList().size());
    for (io.temporal.api.taskqueue.v1.BuildIdReachability BuildReachability :
        reachabilityResponse.getBuildIdReachabilityList()) {
      Set<String> unretrievedTaskQueues = new HashSet<>();
      Map<String, List<TaskReachability>> retrievedTaskQueues = new HashMap<>();
      for (io.temporal.api.taskqueue.v1.TaskQueueReachability taskQueueReachability :
          BuildReachability.getTaskQueueReachabilityList()) {
        if (taskQueueReachability.getReachabilityList().size() == 1
            && taskQueueReachability.getReachabilityList().get(0)
                == TaskReachability.TASK_REACHABILITY_UNSPECIFIED) {
          unretrievedTaskQueues.add(taskQueueReachability.getTaskQueue());
        } else {
          retrievedTaskQueues.put(
              taskQueueReachability.getTaskQueue(), taskQueueReachability.getReachabilityList());
        }
      }
      buildIdReachability.put(
          BuildReachability.getBuildId(),
          new BuildIdReachability(retrievedTaskQueues, unretrievedTaskQueues));
    }
  }

  /**
   * @return Returns a map of Build IDs to information about their reachability.
   */
  public Map<String, BuildIdReachability> getBuildIdReachability() {
    return Collections.unmodifiableMap(buildIdReachability);
  }
}
