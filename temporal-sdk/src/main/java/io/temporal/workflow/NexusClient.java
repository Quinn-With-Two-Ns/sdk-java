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

import io.temporal.common.Experimental;

/**
 * NexusClient is a client for executing Nexus Operations from a workflow. Created through {@link
 * Workflow#newNexusClient}.
 */
@Experimental
public interface NexusClient {
  /**
   * Get the endpoint of the nexus client.
   *
   * @return endpoint of the nexus client.
   */
  String getEndpoint();

  /**
   * Creates a service stub that can be used to start operations on the given service interface.
   *
   * @param service interface that given service implements.
   */
  <T> T newServiceStub(Class<T> service);

  /**
   * Creates a service stub that can be used to start operations on the given service interface.
   *
   * @param service interface that given service implements.
   * @param options options passed to the nexus operations.
   */
  <T> T newServiceStub(Class<T> service, NexusOperationOptions options);

  /**
   * Creates untyped client stub that can be used to execute a Nexus operation.
   *
   * @param service name of the service the operation is part of.
   * @param operation name of the operation to start.
   * @param options options passed to the nexus operation.
   */
  NexusOperationStub newUntypedNexusOperationStub(
      String service, String operation, NexusOperationOptions options);
}
