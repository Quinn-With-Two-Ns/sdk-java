package io.temporal.testing;

import com.google.common.annotations.VisibleForTesting;
import io.temporal.workflow.NexusServiceOptions;

@VisibleForTesting
public interface TestNexusOperationEnvironment {

  static TestNexusOperationEnvironment newInstance() {
    return newInstance(TestEnvironmentOptions.getDefaultInstance());
  }

  static TestNexusOperationEnvironment newInstance(TestEnvironmentOptions options) {
    return new TestNexusOperationEnvironmentInternal(options);
  }

  void registerNexusServiceImplementations(Object... implementations);

  <T> T newNexusServiceStub(Class<T> service);

  <T> T newNexusServiceStub(Class<T> service, NexusServiceOptions options);

  void start();

  void close();
}
