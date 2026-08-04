package com.example.thirdparty.paygate;

/**
 * Runs the fake gateway standalone, so the whole round trip — request, callback, reconciliation — can
 * be driven by hand instead of only from a test.
 *
 * <pre>
 * java -jar target/s07-gateway-stub-0.1.0-SNAPSHOT.jar 18072 s07-gateway-secret \
 *     http://localhost:18070/gateway-callbacks/charges
 *
 * # then, to make it misbehave:
 * curl -sS -X POST localhost:18072/_control -d '{"mode":"SILENT"}'
 * </pre>
 */
public final class GatewayStubMain {

  private GatewayStubMain() {}

  public static void main(String[] args) throws InterruptedException {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 18072;
    String secret = args.length > 1 ? args[1] : "s07-gateway-secret";
    FakePaymentGateway gateway = FakePaymentGateway.start(port, secret);
    if (args.length > 2) {
      gateway.callbacksTo(args[2]);
    }
    System.out.println("fake payment gateway listening on " + gateway.baseUrl());
    System.out.println("modes: " + java.util.Arrays.toString(GatewayMode.values()));
    Thread.currentThread().join();
  }
}
