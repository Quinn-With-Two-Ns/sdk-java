package io.temporal.internal.testing;

import static org.junit.Assert.assertEquals;

import io.nexusrpc.OperationUnsuccessfulException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.testing.TestNexusOperationEnvironment;
import io.temporal.workflow.shared.TestNexusServices;
import org.junit.*;
import org.junit.rules.Timeout;

public class NexusTestingTest {

  private TestNexusOperationEnvironment testEnvironment;

  public @Rule Timeout timeout = Timeout.seconds(10);

  @Before
  public void setUp() {
    testEnvironment = TestNexusOperationEnvironment.newInstance();
  }

  @After
  public void tearDown() throws Exception {
    testEnvironment.close();
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      // Implemented inline
      return OperationHandler.sync((ctx, details, name) -> "Hello, " + name + "!");
    }
  }

  @Test
  public void testSyncSuccess() {
    testEnvironment.registerNexusServiceImplementations(new TestNexusServiceImpl());
    testEnvironment.start();
    TestNexusServices.TestNexusService1 service =
        testEnvironment.newNexusServiceStub(TestNexusServices.TestNexusService1.class);
    String result = service.operation("world");
    assertEquals("Hello, world!", result);
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceFailureImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      // Implemented inline
      return OperationHandler.sync(
          (ctx, details, name) -> {
            throw new OperationUnsuccessfulException("test failure");
          });
    }
  }

  @Test
  public void testSyncFailure() {
    testEnvironment.registerNexusServiceImplementations(new TestNexusServiceFailureImpl());
    testEnvironment.start();
    TestNexusServices.TestNexusService1 service =
        testEnvironment.newNexusServiceStub(TestNexusServices.TestNexusService1.class);
    NexusOperationFailure ex =
        Assert.assertThrows(NexusOperationFailure.class, () -> service.operation("world"));
    System.out.println(ex);
  }
}
