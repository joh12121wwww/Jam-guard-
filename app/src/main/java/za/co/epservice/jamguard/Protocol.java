package za.co.epservice.jamguard;

import java.util.*;

/** Pure Java parser shared by the live service and executable regression tests. */
public final class Protocol {
  public static final class Reading {
    public final Double rssi;
    public final Boolean alarm;
    public final String fault;

    Reading(Double r, Boolean a, String f) {
      rssi = r;
      alarm = a;
      fault = f;
    }
  }

  public static Reading parse(String line) {
    String[] p = line.trim().split(",");
    if (p.length == 0) return null;
    try {
      if (p[0].equals("ERR")) return new Reading(null, null, p.length > 1 ? p[1] : "DEVICE");
      if (p[0].equals("CLEAR")) return new Reading(p.length > 1 ? valid(p[1]) : null, false, null);
      if (p.length < 2) return null;
      Double r = valid(p[1]);
      if (r == null) return null;
      if (p[0].equals("R") && p.length == 3 && (p[2].equals("0") || p[2].equals("1")))
        return new Reading(r, p[2].equals("1"), null);
      if (p[0].equals("RSSI")) return new Reading(r, null, null);
      if (p[0].equals("ALARM") || p[0].equals("ALERT")) return new Reading(r, true, null);
    } catch (RuntimeException ignored) {
    }
    return null;
  }

  private static Double valid(String s) {
    double r = Double.parseDouble(s.trim());
    return Double.isFinite(r) && r >= -150 && r <= 10 ? r : null;
  }

  public static final class Framer {
    private final StringBuilder b = new StringBuilder();
    private boolean dropping;

    public List<String> accept(String chunk) {
      List<String> out = new ArrayList<>();
      for (char c : chunk.toCharArray()) {
        if (c == '\n') {
          if (!dropping && b.length() > 0) out.add(b.toString());
          b.setLength(0);
          dropping = false;
        } else if (c != '\r' && !dropping) {
          if (b.length() >= 16384) {
            b.setLength(0);
            dropping = true;
          } else b.append(c);
        }
      }
      return out;
    }
  }
}
