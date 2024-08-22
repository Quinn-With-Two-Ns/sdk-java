package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.lang.reflect.Type;
import java.util.Optional;

@Experimental
public interface NexusOperationStub {

  Promise<Optional<String>> getExecution();

  NexusOperationOptions getOptions();

  <R> R execute(Class<R> resultClass, Object arg);

  <R> R execute(Class<R> resultClass, Type resultType, Object arg);

  <R> Promise<R> executeAsync(Class<R> resultClass, Object arg);

  <R> Promise<R> executeAsync(Class<R> resultClass, Type resultType, Object arg);
}
