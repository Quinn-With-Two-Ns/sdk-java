package io.temporal.spring.boot.autoconfigure.template;

import static io.temporal.serviceclient.CheckedExceptionWrapper.wrap;

import com.google.common.base.Preconditions;
import io.nexusrpc.ServiceDefinition;
import io.nexusrpc.handler.ServiceImplInstance;
import io.opentracing.Tracer;
import io.temporal.client.WorkflowClient;
import io.temporal.common.Experimental;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.metadata.POJOActivityImplMetadata;
import io.temporal.common.metadata.POJOWorkflowImplMetadata;
import io.temporal.common.metadata.POJOWorkflowMethodMetadata;
import io.temporal.internal.common.env.ReflectionUtils;
import io.temporal.internal.sync.POJOWorkflowImplementationFactory;
import io.temporal.spring.boot.ActivityImpl;
import io.temporal.spring.boot.NexusServiceImpl;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.spring.boot.autoconfigure.properties.NamespaceProperties;
import io.temporal.spring.boot.autoconfigure.properties.WorkerProperties;
import io.temporal.worker.*;
import io.temporal.workflow.DynamicWorkflow;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionValidationException;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.Assert;

/** Creates a {@link WorkerFactory} and Workers for a given namespace provided by WorkflowClient. */
public class WorkersTemplate implements BeanFactoryAware, EnvironmentAware {
  private static final Logger log = LoggerFactory.getLogger(WorkersTemplate.class);

  private final @Nonnull NamespaceProperties namespaceProperties;
  private final ClientTemplate clientTemplate;
  private final @Nullable List<WorkerInterceptor> workerInterceptors;
  private final @Nullable Tracer tracer;
  // if not null, we work with an environment with defined test server
  private final @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment;

  private final @Nullable TemporalOptionsCustomizer<WorkerFactoryOptions.Builder>
      workerFactoryCustomizer;

  private final @Nullable TemporalOptionsCustomizer<WorkerOptions.Builder> workerCustomizer;
  private final @Nullable TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
      workflowImplementationCustomizer;

  private ConfigurableListableBeanFactory beanFactory;
  private Environment environment;

  private WorkerFactory workerFactory;
  private Collection<Worker> workers;
  private final Map<String, RegisteredInfo> registeredInfo = new HashMap<>();

  public WorkersTemplate(
      @Nonnull NamespaceProperties namespaceProperties,
      @Nullable ClientTemplate clientTemplate,
      @Nullable List<WorkerInterceptor> workerInterceptors,
      @Nullable Tracer tracer,
      @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment,
      @Nullable TemporalOptionsCustomizer<WorkerFactoryOptions.Builder> workerFactoryCustomizer,
      @Nullable TemporalOptionsCustomizer<WorkerOptions.Builder> workerCustomizer,
      @Nullable
          TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
              workflowImplementationCustomizer) {
    this.namespaceProperties = namespaceProperties;
    this.workerInterceptors = workerInterceptors;
    this.tracer = tracer;
    this.testWorkflowEnvironment = testWorkflowEnvironment;
    this.clientTemplate = clientTemplate;

    this.workerFactoryCustomizer = workerFactoryCustomizer;
    this.workerCustomizer = workerCustomizer;
    this.workflowImplementationCustomizer = workflowImplementationCustomizer;
  }

  public NamespaceProperties getNamespaceProperties() {
    return namespaceProperties;
  }

  public WorkerFactory getWorkerFactory() {
    if (workerFactory == null) {
      this.workerFactory = createWorkerFactory(clientTemplate.getWorkflowClient());
    }
    return workerFactory;
  }

  public Collection<Worker> getWorkers() {
    if (workers == null) {
      this.workers = createWorkers(getWorkerFactory());
    }
    return workers;
  }

  /** Return information on registered workflow and activity types per task queue */
  @Experimental
  public Map<String, RegisteredInfo> getRegisteredInfo() {
    if (workers == null) {
      this.workers = createWorkers(getWorkerFactory());
    }
    return registeredInfo;
  }

  WorkerFactory createWorkerFactory(WorkflowClient workflowClient) {
    if (testWorkflowEnvironment != null) {
      return testWorkflowEnvironment.getWorkerFactory();
    } else {
      WorkerFactoryOptions workerFactoryOptions =
          new WorkerFactoryOptionsTemplate(
                  namespaceProperties, workerInterceptors, tracer, workerFactoryCustomizer)
              .createWorkerFactoryOptions();
      return WorkerFactory.newInstance(workflowClient, workerFactoryOptions);
    }
  }

  private Collection<Worker> createWorkers(WorkerFactory workerFactory) {
    Workers workers = new Workers();

    // explicitly configured workflow implementations
    if (namespaceProperties.getWorkers() != null) {
      namespaceProperties
          .getWorkers()
          .forEach(
              workerProperties ->
                  createWorkerFromAnExplicitConfig(workerFactory, workerProperties, workers));
    }

    if (namespaceProperties.getWorkersAutoDiscovery() != null
        && namespaceProperties.getWorkersAutoDiscovery().getPackages() != null) {
      Collection<Class<?>> autoDiscoveredWorkflowImplementationClasses =
          autoDiscoverWorkflowImplementations();
      Map<String, Object> autoDiscoveredActivityBeans = autoDiscoverActivityBeans();
      Map<String, Object> autoDiscoveredNexusServiceBeans = autoDiscoverNexusServiceBeans();

      configureWorkflowImplementationsByTaskQueue(
          workerFactory, workers, autoDiscoveredWorkflowImplementationClasses);
      configureActivityBeansByTaskQueue(workerFactory, workers, autoDiscoveredActivityBeans);
      configureNexusServiceBeansByTaskQueue(
          workerFactory, workers, autoDiscoveredNexusServiceBeans);
      configureWorkflowImplementationsByWorkerName(
          workers, autoDiscoveredWorkflowImplementationClasses);
      configureActivityBeansByWorkerName(workers, autoDiscoveredActivityBeans);
      configureNexusServiceBeansByWorkerName(workers, autoDiscoveredNexusServiceBeans);
    }

    return workers.getWorkers();
  }

  private void configureWorkflowImplementationsByTaskQueue(
      WorkerFactory workerFactory,
      Workers workers,
      Collection<Class<?>> autoDiscoveredWorkflowImplementationClasses) {
    for (Class<?> clazz : autoDiscoveredWorkflowImplementationClasses) {
      WorkflowImpl annotation = clazz.getAnnotation(WorkflowImpl.class);
      for (String taskQueue : annotation.taskQueues()) {
        taskQueue = environment.resolvePlaceholders(taskQueue);
        Worker worker = workerFactory.tryGetWorker(taskQueue);
        if (worker == null) {
          log.info(
              "Creating a worker with default settings for a task queue '{}' "
                  + "caused by an auto-discovered workflow class {}",
              taskQueue,
              clazz);

          worker = createNewWorker(taskQueue, null, workers);
        }

        configureWorkflowImplementationAutoDiscovery(worker, clazz, null, workers);
      }
    }
  }

  private void configureActivityBeansByTaskQueue(
      WorkerFactory workerFactory,
      Workers workers,
      Map<String, Object> autoDiscoveredActivityBeans) {
    autoDiscoveredActivityBeans.forEach(
        (beanName, bean) -> {
          Class<?> targetClass = AopUtils.getTargetClass(bean);
          ActivityImpl annotation = AnnotationUtils.findAnnotation(targetClass, ActivityImpl.class);
          if (annotation != null) {
            for (String taskQueue : annotation.taskQueues()) {
              taskQueue = environment.resolvePlaceholders(taskQueue);
              Worker worker = workerFactory.tryGetWorker(taskQueue);
              if (worker == null) {
                log.info(
                    "Creating a worker with default settings for a task queue '{}' "
                        + "caused by an auto-discovered activity class {}",
                    taskQueue,
                    targetClass);
                worker = createNewWorker(taskQueue, null, workers);
              }

              configureActivityImplementationAutoDiscovery(
                  worker, bean, beanName, targetClass, null, workers);
            }
          }
        });
  }

  private void configureNexusServiceBeansByTaskQueue(
      WorkerFactory workerFactory,
      Workers workers,
      Map<String, Object> autoDiscoveredNexusServiceBeans) {
    autoDiscoveredNexusServiceBeans.forEach(
        (beanName, bean) -> {
          Class<?> targetClass = AopUtils.getTargetClass(bean);
          NexusServiceImpl annotation =
              AnnotationUtils.findAnnotation(targetClass, NexusServiceImpl.class);
          if (annotation != null) {
            for (String taskQueue : annotation.taskQueues()) {
              taskQueue = environment.resolvePlaceholders(taskQueue);
              Worker worker = workerFactory.tryGetWorker(taskQueue);
              if (worker == null) {
                log.info(
                    "Creating a worker with default settings for a task queue '{}' "
                        + "caused by an auto-discovered nexus service class {}",
                    taskQueue,
                    targetClass);
                worker = createNewWorker(taskQueue, null, workers);
              }

              configureNexusServiceImplementationAutoDiscovery(
                  worker, bean, beanName, targetClass, null, workers);
            }
          }
        });
  }

  private void configureWorkflowImplementationsByWorkerName(
      Workers workers, Collection<Class<?>> autoDiscoveredWorkflowImplementationClasses) {
    for (Class<?> clazz : autoDiscoveredWorkflowImplementationClasses) {
      WorkflowImpl annotation = clazz.getAnnotation(WorkflowImpl.class);

      for (String workerName : annotation.workers()) {
        Worker worker = workers.getByName(workerName);
        if (worker == null) {
          throw new BeanDefinitionValidationException(
              "Worker with name "
                  + workerName
                  + " is not found in the config, but is referenced by auto-discovered workflow implementation class "
                  + clazz);
        }

        configureWorkflowImplementationAutoDiscovery(worker, clazz, workerName, workers);
      }
    }
  }

  private void configureActivityBeansByWorkerName(
      Workers workers, Map<String, Object> autoDiscoveredActivityBeans) {
    autoDiscoveredActivityBeans.forEach(
        (beanName, bean) -> {
          Class<?> targetClass = AopUtils.getTargetClass(bean);
          ActivityImpl annotation = AnnotationUtils.findAnnotation(targetClass, ActivityImpl.class);
          if (annotation != null) {
            for (String workerName : annotation.workers()) {
              Worker worker = workers.getByName(workerName);
              if (worker == null) {
                throw new BeanDefinitionValidationException(
                    "Worker with name "
                        + workerName
                        + " is not found in the config, but is referenced by auto-discovered activity bean "
                        + beanName);
              }

              configureActivityImplementationAutoDiscovery(
                  worker, bean, beanName, targetClass, workerName, workers);
            }
          }
        });
  }

  private void configureNexusServiceBeansByWorkerName(
      Workers workers, Map<String, Object> autoDiscoveredNexusServiceBeans) {
    autoDiscoveredNexusServiceBeans.forEach(
        (beanName, bean) -> {
          Class<?> targetClass = AopUtils.getTargetClass(bean);
          NexusServiceImpl annotation =
              AnnotationUtils.findAnnotation(targetClass, NexusServiceImpl.class);
          if (annotation != null) {
            for (String workerName : annotation.workers()) {
              Worker worker = workers.getByName(workerName);
              if (worker == null) {
                throw new BeanDefinitionValidationException(
                    "Worker with name "
                        + workerName
                        + " is not found in the config, but is referenced by auto-discovered nexus service bean "
                        + beanName);
              }

              configureNexusServiceImplementationAutoDiscovery(
                  worker, bean, beanName, targetClass, workerName, workers);
            }
          }
        });
  }

  private Collection<Class<?>> autoDiscoverWorkflowImplementations() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false, environment);
    scanner.addIncludeFilter(new AnnotationTypeFilter(WorkflowImpl.class));
    Set<Class<?>> implementations = new HashSet<>();
    for (String pckg : namespaceProperties.getWorkersAutoDiscovery().getPackages()) {
      Set<BeanDefinition> candidateComponents = scanner.findCandidateComponents(pckg);
      for (BeanDefinition beanDefinition : candidateComponents) {
        try {
          implementations.add(Class.forName(beanDefinition.getBeanClassName()));
        } catch (ClassNotFoundException e) {
          throw new BeanDefinitionValidationException(
              "Fail loading class for bean definition " + beanDefinition, e);
        }
      }
    }
    return implementations;
  }

  private Map<String, Object> autoDiscoverActivityBeans() {
    return beanFactory.getBeansWithAnnotation(ActivityImpl.class);
  }

  private Map<String, Object> autoDiscoverNexusServiceBeans() {
    return beanFactory.getBeansWithAnnotation(NexusServiceImpl.class);
  }

  private void createWorkerFromAnExplicitConfig(
      WorkerFactory workerFactory, WorkerProperties workerProperties, Workers workers) {
    String taskQueue = workerProperties.getTaskQueue();
    if (workerFactory.tryGetWorker(taskQueue) != null) {
      throw new BeanDefinitionValidationException(
          "Worker for the task queue "
              + taskQueue
              + " already exists. Duplicate workers in the config?");
    }
    log.info("Creating configured worker for a task queue {}", taskQueue);
    Worker worker = createNewWorker(taskQueue, workerProperties, workers);

    Collection<Class<?>> workflowClasses = workerProperties.getWorkflowClasses();
    if (workflowClasses != null) {
      workflowClasses.forEach(
          clazz -> {
            log.info(
                "Registering configured workflow class {} on a task queue '{}'", clazz, taskQueue);
            configureWorkflowImplementation(worker, clazz);
          });
    }

    Collection<String> activityBeans = workerProperties.getActivityBeans();
    if (activityBeans != null) {
      activityBeans.forEach(
          beanName -> {
            Object bean = beanFactory.getBean(beanName);
            log.info(
                "Registering configured activity bean '{}' of a {} class on task queue '{}'",
                beanName,
                AopUtils.getTargetClass(bean),
                taskQueue);
            worker.registerActivitiesImplementations(bean);
            POJOActivityImplMetadata activityImplMetadata =
                POJOActivityImplMetadata.newInstance(AopUtils.getTargetClass(bean));
            addRegisteredActivityImpl(
                worker, beanName, bean.getClass().getName(), activityImplMetadata);
          });
    }

    Collection<String> nexusServiceBeans = workerProperties.getNexusServiceBeans();
    if (nexusServiceBeans != null) {
      nexusServiceBeans.forEach(
          beanName -> {
            Object bean = beanFactory.getBean(beanName);
            log.info(
                "Registering configured nexus service bean '{}' of a {} class on task queue '{}'",
                beanName,
                AopUtils.getTargetClass(bean),
                taskQueue);
            worker.registerNexusServiceImplementation(bean);
            addRegisteredNexusServiceImpl(
                worker,
                beanName,
                bean.getClass().getName(),
                ServiceImplInstance.fromInstance(AopUtils.getTargetClass(bean)).getDefinition());
          });
    }
  }

  private void configureActivityImplementationAutoDiscovery(
      Worker worker,
      Object bean,
      String beanName,
      Class<?> targetClass,
      String byWorkerName,
      Workers workers) {
    try {
      if (registeredInfo.containsKey(worker.getTaskQueue())
          && registeredInfo.get(worker.getTaskQueue()).isActivityRegistered(beanName)) {
        if (log.isInfoEnabled()) {
          log.debug(
              "Activity bean {} is already registered on a worker {} with a task queue '{}'",
              beanName,
              byWorkerName != null ? "'" + byWorkerName + "' " : "",
              worker.getTaskQueue());
        }
        return; // already registered
      }
      worker.registerActivitiesImplementations(bean);
      POJOActivityImplMetadata activityImplMetadata =
          POJOActivityImplMetadata.newInstance(AopUtils.getTargetClass(bean));
      addRegisteredActivityImpl(worker, beanName, bean.getClass().getName(), activityImplMetadata);
      if (log.isInfoEnabled()) {
        log.info(
            "Registering auto-discovered activity bean '{}' of class {} on a worker {} with a task queue '{}'",
            beanName,
            targetClass,
            byWorkerName != null ? "'" + byWorkerName + "' " : "",
            worker.getTaskQueue());
      }
    } catch (TypeAlreadyRegisteredException registeredEx) {
      if (!namespaceProperties.isIgnoreDuplicateDefinitions()) {
        throw registeredEx;
      }
      if (log.isInfoEnabled()) {
        log.info(
            "Skipping auto-discovered activity bean '{}' of class {} on a worker {} with a task queue '{}'"
                + " as activity type '{}' is already registered on the worker",
            beanName,
            targetClass,
            byWorkerName != null ? "'" + byWorkerName + "' " : "",
            worker.getTaskQueue(),
            registeredEx.getRegisteredTypeName());
      }
    }
  }

  private void configureNexusServiceImplementationAutoDiscovery(
      Worker worker,
      Object bean,
      String beanName,
      Class<?> targetClass,
      String byWorkerName,
      Workers workers) {
    try {
      if (registeredInfo.containsKey(worker.getTaskQueue())
          && registeredInfo.get(worker.getTaskQueue()).isNexusServiceRegistered(beanName)) {
        if (log.isInfoEnabled()) {
          log.debug(
              "Nexus service bean {} is already registered on a worker {} with a task queue '{}'",
              beanName,
              byWorkerName != null ? "'" + byWorkerName + "' " : "",
              worker.getTaskQueue());
        }
        return; // already registered
      }
      worker.registerNexusServiceImplementation(bean);
      addRegisteredNexusServiceImpl(
          worker,
          beanName,
          bean.getClass().getName(),
          ServiceImplInstance.fromInstance(bean).getDefinition());
      if (log.isInfoEnabled()) {
        log.info(
            "Registering auto-discovered nexus service bean '{}' of class {} on a worker {} with a task queue '{}'",
            beanName,
            targetClass,
            byWorkerName != null ? "'" + byWorkerName + "' " : "",
            worker.getTaskQueue());
      }
    } catch (TypeAlreadyRegisteredException registeredEx) {
      if (!namespaceProperties.isIgnoreDuplicateDefinitions()) {
        throw registeredEx;
      }
      if (log.isInfoEnabled()) {
        log.info(
            "Skipping auto-discovered nexus service bean '{}' of class {} on a worker {} with a task queue '{}'"
                + " as nexus service type '{}' is already registered on the worker",
            beanName,
            targetClass,
            byWorkerName != null ? "'" + byWorkerName + "' " : "",
            worker.getTaskQueue(),
            registeredEx.getRegisteredTypeName());
      }
    }
  }

  private void configureWorkflowImplementationAutoDiscovery(
      Worker worker, Class<?> clazz, String byWorkerName, Workers workers) {
    try {
      if (registeredInfo.containsKey(worker.getTaskQueue())
          && registeredInfo.get(worker.getTaskQueue()).isWorkflowRegistered(clazz)) {
        if (log.isInfoEnabled()) {
          log.debug(
              "Workflow class {} is already registered on a worker {} with a task queue '{}'",
              clazz,
              byWorkerName != null ? "'" + byWorkerName + "' " : "",
              worker.getTaskQueue());
        }
        return; // already registered
      }
      configureWorkflowImplementation(worker, clazz);
      if (log.isInfoEnabled()) {
        log.info(
            "Registering auto-discovered workflow class {} on a worker {} with a task queue '{}'",
            clazz,
            byWorkerName != null ? "'" + byWorkerName + "' " : "",
            worker.getTaskQueue());
      }
    } catch (TypeAlreadyRegisteredException registeredEx) {
      if (!namespaceProperties.isIgnoreDuplicateDefinitions()) {
        throw registeredEx;
      }
      if (log.isInfoEnabled()) {
        log.info(
            "Skipping registering of auto-discovered workflow class {} on a worker {} with a task queue '{}' "
                + "as workflow type '{}' is already registered on the worker",
            clazz,
            byWorkerName != null ? "'" + byWorkerName + "' " : "",
            worker.getTaskQueue(),
            registeredEx.getRegisteredTypeName());
      }
    }
  }

  @SuppressWarnings("unchecked")
  private <T> void configureWorkflowImplementation(Worker worker, Class<?> clazz) {
    // Handle dynamic workflows
    if (DynamicWorkflow.class.isAssignableFrom(clazz)) {
      try {
        Method executeMethod = clazz.getMethod("execute", EncodedValues.class);
        Optional<Constructor<?>> ctor =
            ReflectionUtils.getWorkflowInitConstructor(
                clazz, Collections.singletonList(executeMethod));
        WorkflowImplementationOptions workflowImplementationOptions =
            new WorkflowImplementationOptionsTemplate(workflowImplementationCustomizer)
                .createWorkflowImplementationOptions(worker, clazz, null);
        worker.registerWorkflowImplementationFactory(
            DynamicWorkflow.class,
            (encodedValues) -> {
              if (ctor.isPresent()) {
                try {
                  return (DynamicWorkflow) ctor.get().newInstance(encodedValues);
                } catch (InstantiationException
                    | IllegalAccessException
                    | InvocationTargetException e) {
                  throw wrap(e);
                }
              } else {
                try {
                  return (DynamicWorkflow) clazz.getDeclaredConstructor().newInstance();
                } catch (NoSuchMethodException
                    | InstantiationException
                    | IllegalAccessException
                    | InvocationTargetException e) {
                  // Error to fail workflow task as this can be fixed by a new deployment.
                  throw new Error(
                      "Failure instantiating workflow implementation class " + clazz.getName(), e);
                }
              }
            },
            workflowImplementationOptions);
        return;
      } catch (NoSuchMethodException e) {
        throw new BeanDefinitionValidationException(
            "Dynamic workflow implementation doesn't have execute method: " + clazz, e);
      }
    }

    POJOWorkflowImplMetadata workflowMetadata =
        POJOWorkflowImplMetadata.newInstanceForWorkflowFactory(clazz);
    List<POJOWorkflowMethodMetadata> workflowMethods = workflowMetadata.getWorkflowMethods();
    if (workflowMethods.isEmpty()) {
      throw new BeanDefinitionValidationException(
          "Workflow implementation doesn't implement any interface "
              + "with a workflow method annotated with @WorkflowMethod: "
              + clazz);
    }

    WorkerDeploymentOptions deploymentOptions = worker.getWorkerOptions().getDeploymentOptions();

    // If the workflow implementation class has a constructor annotated with @WorkflowInit,
    // we need to register it as a workflow factory.
    if (workflowMetadata.getWorkflowInit() != null) {
      // Currently, we only support one workflow method in a class with a constructor annotated with
      // @WorkflowInit.
      if (workflowMethods.size() > 1) {
        throw new BeanDefinitionValidationException(
            "Workflow implementation class "
                + clazz
                + " has more then one workflow method and a constructor annotated with @WorkflowInit.");
      }
      POJOWorkflowMethodMetadata workflowMethod = workflowMetadata.getWorkflowMethods().get(0);
      if (deploymentOptions != null && deploymentOptions.isUsingVersioning()) {
        POJOWorkflowImplementationFactory.validateVersioningBehavior(
            clazz,
            workflowMethod,
            deploymentOptions.getDefaultVersioningBehavior(),
            deploymentOptions.isUsingVersioning());
      }

      WorkflowImplementationOptions workflowImplementationOptions =
          new WorkflowImplementationOptionsTemplate(workflowImplementationCustomizer)
              .createWorkflowImplementationOptions(worker, clazz, workflowMethod);
      worker.registerWorkflowImplementationFactory(
          (Class<T>) workflowMethod.getWorkflowInterface(),
          (encodedValues) -> {
            try {
              Constructor<?> ctor = workflowMetadata.getWorkflowInit();
              Object[] parameters = new Object[ctor.getParameterCount()];
              for (int i = 0; i < ctor.getParameterCount(); i++) {
                parameters[i] =
                    encodedValues.get(
                        i, ctor.getParameterTypes()[i], ctor.getGenericParameterTypes()[i]);
              }
              T workflowInstance = (T) workflowMetadata.getWorkflowInit().newInstance(parameters);
              beanFactory.autowireBean(workflowInstance);
              return workflowInstance;
            } catch (InstantiationException
                | IllegalAccessException
                | InvocationTargetException e) {
              throw new RuntimeException(e);
            }
          },
          workflowImplementationOptions);
      addRegisteredWorkflowImpl(
          worker, clazz, workflowMethod.getWorkflowInterface().getName(), workflowMetadata);
    } else {
      for (POJOWorkflowMethodMetadata workflowMethod : workflowMetadata.getWorkflowMethods()) {
        if (deploymentOptions != null && deploymentOptions.isUsingVersioning()) {
          POJOWorkflowImplementationFactory.validateVersioningBehavior(
              clazz,
              workflowMethod,
              deploymentOptions.getDefaultVersioningBehavior(),
              deploymentOptions.isUsingVersioning());
        }
        WorkflowImplementationOptions workflowImplementationOptions =
            new WorkflowImplementationOptionsTemplate(workflowImplementationCustomizer)
                .createWorkflowImplementationOptions(worker, clazz, workflowMethod);
        worker.registerWorkflowImplementationFactory(
            (Class<T>) workflowMethod.getWorkflowInterface(),
            () -> (T) beanFactory.createBean(clazz),
            workflowImplementationOptions);
        addRegisteredWorkflowImpl(
            worker, clazz, workflowMethod.getWorkflowInterface().getName(), workflowMetadata);
      }
    }
  }

  @Override
  public void setBeanFactory(@Nonnull BeanFactory beanFactory) throws BeansException {
    Assert.isInstanceOf(ConfigurableListableBeanFactory.class, beanFactory);
    this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
  }

  @Override
  public void setEnvironment(@Nonnull Environment environment) {
    this.environment = environment;
  }

  private Worker createNewWorker(
      @Nonnull String taskQueue, @Nullable WorkerProperties properties, @Nonnull Workers workers) {
    Preconditions.checkState(
        workerFactory.tryGetWorker(taskQueue) == null,
        "[BUG] This method should never be called twice for the same Task Queue='%s'",
        taskQueue);

    String workerName =
        properties != null && properties.getName() != null ? properties.getName() : taskQueue;

    WorkerOptions workerOptions =
        new WorkerOptionsTemplate(workerName, taskQueue, properties, workerCustomizer)
            .createWorkerOptions();
    Worker worker = workerFactory.newWorker(taskQueue, workerOptions);
    workers.addWorker(workerName, worker);
    return worker;
  }

  private void addRegisteredWorkflowImpl(
      Worker worker, Class<?> clazz, String workflowClass, POJOWorkflowImplMetadata metadata) {
    registeredInfo
        .computeIfAbsent(worker.getTaskQueue(), (k) -> new RegisteredInfo())
        .addWorkflowInfo(
            new RegisteredWorkflowInfo()
                .addImplementationClass(clazz)
                .addClassName(workflowClass)
                .addMetadata(metadata));
  }

  private void addRegisteredActivityImpl(
      Worker worker, String beanName, String beanClass, POJOActivityImplMetadata metadata) {
    registeredInfo
        .computeIfAbsent(worker.getTaskQueue(), (k) -> new RegisteredInfo())
        .addActivityInfo(
            new RegisteredActivityInfo()
                .addBeanName(beanName)
                .addClassName(beanClass)
                .addMetadata(metadata));
  }

  private void addRegisteredNexusServiceImpl(
      Worker worker, String beanName, String beanClass, ServiceDefinition serviceDefinition) {
    registeredInfo
        .computeIfAbsent(worker.getTaskQueue(), (k) -> new RegisteredInfo())
        .addNexusServiceInfo(
            new RegisteredNexusServiceInfo()
                .addBeanName(beanName)
                .addClassName(beanClass)
                .addDefinition(serviceDefinition));
  }

  public static class RegisteredInfo {
    private final HashMap<Class<?>, RegisteredWorkflowInfo> registeredWorkflowInfo =
        new HashMap<>();
    private final HashMap<String, RegisteredActivityInfo> registeredActivityInfo = new HashMap<>();
    private final HashMap<String, RegisteredNexusServiceInfo> registeredNexusServiceInfos =
        new HashMap<>();

    public RegisteredInfo addActivityInfo(RegisteredActivityInfo activityInfo) {
      registeredActivityInfo.put(activityInfo.getBeanName(), activityInfo);
      return this;
    }

    public RegisteredInfo addNexusServiceInfo(RegisteredNexusServiceInfo nexusServiceInfo) {
      registeredNexusServiceInfos.put(nexusServiceInfo.getBeanName(), nexusServiceInfo);
      return this;
    }

    public RegisteredInfo addWorkflowInfo(RegisteredWorkflowInfo workflowInfo) {
      registeredWorkflowInfo.put(workflowInfo.getImplementationClass(), workflowInfo);
      return this;
    }

    public boolean isWorkflowRegistered(Class<?> workflowClass) {
      return registeredWorkflowInfo.containsKey(workflowClass);
    }

    public boolean isActivityRegistered(String beanName) {
      return registeredActivityInfo.containsKey(beanName);
    }

    public boolean isNexusServiceRegistered(String beanName) {
      return registeredNexusServiceInfos.containsKey(beanName);
    }

    public List<RegisteredActivityInfo> getRegisteredActivityInfo() {
      return new ArrayList<>(registeredActivityInfo.values());
    }

    public List<RegisteredWorkflowInfo> getRegisteredWorkflowInfo() {
      return new ArrayList<>(registeredWorkflowInfo.values());
    }

    public List<RegisteredNexusServiceInfo> getRegisteredNexusServiceInfos() {
      return new ArrayList<>(registeredNexusServiceInfos.values());
    }
  }

  @Experimental
  public static class RegisteredActivityInfo {
    private String beanName;
    private String className;
    private POJOActivityImplMetadata metadata;

    public RegisteredActivityInfo addClassName(String className) {
      this.className = className;
      return this;
    }

    public RegisteredActivityInfo addBeanName(String beanName) {
      this.beanName = beanName;
      return this;
    }

    public RegisteredActivityInfo addMetadata(POJOActivityImplMetadata metadata) {
      this.metadata = metadata;
      return this;
    }

    public String getClassName() {
      return className;
    }

    public String getBeanName() {
      return beanName;
    }

    public POJOActivityImplMetadata getMetadata() {
      return metadata;
    }
  }

  @Experimental
  public static class RegisteredNexusServiceInfo {
    private String beanName;
    private String className;
    private ServiceDefinition definition;

    public RegisteredNexusServiceInfo addClassName(String className) {
      this.className = className;
      return this;
    }

    public RegisteredNexusServiceInfo addBeanName(String beanName) {
      this.beanName = beanName;
      return this;
    }

    public RegisteredNexusServiceInfo addDefinition(ServiceDefinition definition) {
      this.definition = definition;
      return this;
    }

    public String getClassName() {
      return className;
    }

    public String getBeanName() {
      return beanName;
    }

    public ServiceDefinition getDefinition() {
      return definition;
    }
  }

  @Experimental
  public static class RegisteredWorkflowInfo {
    private Class<?> implementationClass;
    private String className;
    private POJOWorkflowImplMetadata metadata;

    public RegisteredWorkflowInfo addClassName(String className) {
      this.className = className;
      return this;
    }

    public RegisteredWorkflowInfo addImplementationClass(Class<?> implementationClass) {
      this.implementationClass = implementationClass;
      return this;
    }

    public RegisteredWorkflowInfo addMetadata(POJOWorkflowImplMetadata metadata) {
      this.metadata = metadata;
      return this;
    }

    public String getClassName() {
      return className;
    }

    public POJOWorkflowImplMetadata getMetadata() {
      return metadata;
    }

    public Class<?> getImplementationClass() {
      return implementationClass;
    }
  }

  private static class Workers {
    private final Map<String, Worker> workersByName = new HashMap<>();
    private final Map<String, Worker> workersByTaskQueue = new HashMap<>();
    private final List<Worker> workers = new ArrayList<>();

    public void addWorker(@Nonnull String workerName, Worker newWorker) {
      Worker existingWorker = workersByTaskQueue.get(newWorker.getTaskQueue());
      // Caller of this method should make sure that it doesn't try to register a worker for a
      // task queue that already has a worker.
      Preconditions.checkState(
          existingWorker == null,
          "[BUG] Worker with Task Queue='%s' already exists.",
          newWorker.getTaskQueue());

      existingWorker = workersByName.get(workerName);
      if (existingWorker != null) {
        throw new BeanDefinitionValidationException(
            "Worker name "
                + workerName
                + " is shared between Workers on different Task Queues '"
                + existingWorker.getTaskQueue()
                + "' and '"
                + newWorker.getTaskQueue()
                + "'. Worker names should be unique.");
      }

      workers.add(newWorker);
      workersByTaskQueue.put(newWorker.getTaskQueue(), newWorker);
      workersByName.put(workerName, newWorker);
    }

    public List<Worker> getWorkers() {
      return workers;
    }

    @Nullable
    public Worker getByName(String workerName) {
      return workersByName.get(workerName);
    }
  }
}
