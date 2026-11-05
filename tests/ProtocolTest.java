import za.co.epservice.jamguard.Protocol;

public final class ProtocolTest {
  static int checks;

  static void ok(boolean v) {
    checks++;
    if (!v) throw new AssertionError("check " + checks);
  }

  public static void main(String[] a) {
    var r = Protocol.parse("R,-94.5,1");
    ok(r != null && r.alarm && r.rssi == -94.5);
    ok(!Protocol.parse("R,-117,0").alarm);
    ok(Protocol.parse("ERR,RADIO").fault.equals("RADIO"));
    ok(Protocol.parse("RSSI,-100").alarm == null);
    ok(Protocol.parse("ALARM,-48").alarm);
    ok(Protocol.parse("ALERT,-50").alarm);
    ok(!Protocol.parse("CLEAR").alarm);
    ok(!Protocol.parse("CLEAR,-110").alarm);
    for (String bad :
        new String[] {
          "",
          "R,NaN,1",
          "R,Infinity,0",
          "R,-151,1",
          "R,11,0",
          "R,-90,2",
          "R,-90",
          "noise",
          "R,nope,1",
          "{\"id\":1,\"ok\":true}"
        }) ok(Protocol.parse(bad) == null);
    var f = new Protocol.Framer();
    ok(f.accept("{\"id\":").isEmpty());
    ok(f.accept("1}\r\n{\"id\":2}\n").size() == 2);
    ok(f.accept("x".repeat(16385) + "\nVALID\n").equals(java.util.List.of("VALID")));
    System.out.println("PASS: " + checks + " protocol checks");
  }
}
