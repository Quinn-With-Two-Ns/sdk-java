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

package io.temporal.workflow;

import io.nexusrpc.Service;
import io.temporal.common.Experimental;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * NexusOperationStub is used to call a nexus operation without referencing a {@link Service} it
 * implements. This is useful to call operations when their type is not known at compile time or to
 * execute services implemented in other languages. Created through {@link
 * NexusClient#newUntypedNexusOperationStub}.
 */
@Experimental
public interface NexusOperationStub {
  /**
   * Returns a promise that is resolved when the operation reaches the STARTED state. For
   * synchronous operations, this will be resolved at the same time as the promise from
   * executeAsync. For asynchronous operations, this promises is resolved independently. If the
   * operation is unsuccessful, this promise will throw the same exception as executeAsync. Use this
   * method to extract the Operation ID of an asynchronous operation. OperationID will be empty for
   * synchronous operations. If the workflow completes before this promise is ready then the
   * operation might not start at all.
   *
   * @return promise that becomes ready once the operation has started.
   */
  Promise<Optional<String>> getExecution();

  NexusOperationOptions getOptions();

  /**
   * Executes a Nexus Operation
   *
   * @param resultClass class of the operation result
   * @param <R> type of the operation result
   * @return operation result
   * @throws io.temporal.failure.NexusOperationFailure if the operation fails
   */
  <R> R execute(Class<R> resultClass, Object arg);

  /**
   * Executes a Nexus Operation
   *
   * @param resultClass class of the operation result
   * @param resultType type of the operation result. Differs from resultClass for generic types.
   * @param <R> type of the operation result
   * @return operation result
   * @throws io.temporal.failure.NexusOperationFailure if the operation fails
   */
  <R> R execute(Class<R> resultClass, Type resultType, Object arg);

  <R> Promise<R> executeAsync(Class<R> resultClass, Object arg);

  <R> Promise<R> executeAsync(Class<R> resultClass, Type resultType, Object arg);
}
