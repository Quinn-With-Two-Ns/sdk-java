package io.temporal.workflow;

import io.temporal.common.*;
import io.temporal.common.context.ContextPropagator;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * This class contain overrides for continueAsNew call. Every field can be null and it means that
 * the value of the option should be taken from the originating workflow run.
 */
public final class ContinueAsNewOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ContinueAsNewOptions options) {
    return new Builder(options);
  }

  public static ContinueAsNewOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final ContinueAsNewOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = ContinueAsNewOptions.newBuilder().build();
  }

  public static final class Builder {

    private Duration workflowRunTimeout;
    private String taskQueue;
    private RetryOptions retryOptions;
    private Duration workflowTaskTimeout;
    private Map<String, Object> memo;
    private Map<String, Object> searchAttributes;
    private SearchAttributes typedSearchAttributes;
    private List<ContextPropagator> contextPropagators;

    @SuppressWarnings("deprecation")
    private VersioningIntent versioningIntent;

    private Builder() {}

    private Builder(ContinueAsNewOptions options) {
      if (options == null) {
        return;
      }
      this.workflowRunTimeout = options.workflowRunTimeout;
      this.taskQueue = options.taskQueue;
      this.retryOptions = options.retryOptions;
      this.workflowTaskTimeout = options.workflowTaskTimeout;
      this.memo = options.getMemo();
      this.searchAttributes = options.getSearchAttributes();
      this.typedSearchAttributes = options.getTypedSearchAttributes();
      this.contextPropagators = options.getContextPropagators();
      this.versioningIntent = options.versioningIntent;
    }

    public Builder setWorkflowRunTimeout(Duration workflowRunTimeout) {
      this.workflowRunTimeout = workflowRunTimeout;
      return this;
    }

    public Builder setTaskQueue(String taskQueue) {
      this.taskQueue = taskQueue;
      return this;
    }

    public Builder setRetryOptions(RetryOptions retryOptions) {
      this.retryOptions = retryOptions;
      return this;
    }

    public Builder setWorkflowTaskTimeout(Duration workflowTaskTimeout) {
      this.workflowTaskTimeout = workflowTaskTimeout;
      return this;
    }

    public Builder setMemo(Map<String, Object> memo) {
      this.memo = memo;
      return this;
    }

    /**
     * @deprecated use {@link #setTypedSearchAttributes} instead.
     */
    @Deprecated
    public Builder setSearchAttributes(Map<String, Object> searchAttributes) {
      if (searchAttributes != null
          && !searchAttributes.isEmpty()
          && this.typedSearchAttributes != null) {
        throw new IllegalArgumentException(
            "Cannot have search attributes and typed search attributes");
      }
      this.searchAttributes = searchAttributes;
      return this;
    }

    public Builder setTypedSearchAttributes(SearchAttributes typedSearchAttributes) {
      if (typedSearchAttributes != null
          && searchAttributes != null
          && !searchAttributes.isEmpty()) {
        throw new IllegalArgumentException(
            "Cannot have typed search attributes and search attributes");
      }
      this.typedSearchAttributes = typedSearchAttributes;
      return this;
    }

    public Builder setContextPropagators(List<ContextPropagator> contextPropagators) {
      this.contextPropagators = contextPropagators;
      return this;
    }

    /**
     * Specifies whether this continued workflow should run on a worker with a compatible Build Id
     * or not. See the variants of {@link VersioningIntent}.
     *
     * @deprecated Worker Versioning is now deprecated please migrate to the <a
     *     href="https://docs.temporal.io/worker-deployments">Worker Deployment API</a>.
     */
    @Deprecated
    public Builder setVersioningIntent(VersioningIntent versioningIntent) {
      this.versioningIntent = versioningIntent;
      return this;
    }

    public ContinueAsNewOptions build() {
      return new ContinueAsNewOptions(
          workflowRunTimeout,
          taskQueue,
          retryOptions,
          workflowTaskTimeout,
          memo,
          searchAttributes,
          typedSearchAttributes,
          contextPropagators,
          versioningIntent);
    }
  }

  private final @Nullable Duration workflowRunTimeout;
  private final @Nullable String taskQueue;
  private final @Nullable RetryOptions retryOptions;
  private final @Nullable Duration workflowTaskTimeout;
  private final @Nullable Map<String, Object> memo;
  private final @Nullable Map<String, Object> searchAttributes;
  private final @Nullable SearchAttributes typedSearchAttributes;
  private final @Nullable List<ContextPropagator> contextPropagators;

  @SuppressWarnings("deprecation")
  private final @Nullable VersioningIntent versioningIntent;

  public ContinueAsNewOptions(
      @Nullable Duration workflowRunTimeout,
      @Nullable String taskQueue,
      @Nullable RetryOptions retryOptions,
      @Nullable Duration workflowTaskTimeout,
      @Nullable Map<String, Object> memo,
      @Nullable Map<String, Object> searchAttributes,
      @Nullable SearchAttributes typedSearchAttributes,
      @Nullable List<ContextPropagator> contextPropagators,
      @SuppressWarnings("deprecation") @Nullable VersioningIntent versioningIntent) {
    this.workflowRunTimeout = workflowRunTimeout;
    this.taskQueue = taskQueue;
    this.retryOptions = retryOptions;
    this.workflowTaskTimeout = workflowTaskTimeout;
    this.memo = memo;
    this.searchAttributes = searchAttributes;
    this.typedSearchAttributes = typedSearchAttributes;
    this.contextPropagators = contextPropagators;
    this.versioningIntent = versioningIntent;
  }

  public @Nullable Duration getWorkflowRunTimeout() {
    return workflowRunTimeout;
  }

  public @Nullable String getTaskQueue() {
    return taskQueue;
  }

  @Nullable
  public RetryOptions getRetryOptions() {
    return retryOptions;
  }

  public @Nullable Duration getWorkflowTaskTimeout() {
    return workflowTaskTimeout;
  }

  public @Nullable Map<String, Object> getMemo() {
    return memo;
  }

  /**
   * @deprecated use {@link #getSearchAttributes} instead.
   */
  @Deprecated
  public @Nullable Map<String, Object> getSearchAttributes() {
    return searchAttributes;
  }

  public @Nullable SearchAttributes getTypedSearchAttributes() {
    return typedSearchAttributes;
  }

  public @Nullable List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }

  /**
   * @deprecated Worker Versioning is now deprecated please migrate to the <a
   *     href="https://docs.temporal.io/worker-deployments">Worker Deployment API</a>.
   */
  @Deprecated
  public @Nullable VersioningIntent getVersioningIntent() {
    return versioningIntent;
  }
}
