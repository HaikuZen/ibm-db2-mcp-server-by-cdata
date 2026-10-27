package com.cdata.mcp;

public class StringUtil {
  public static String emptyNull(String t) {
    if (t != null && t.length() == 0) {
      return null;
    }
    return t;
  }

  public static boolean isNullOrEmpty(String s) {
    return s == null || s.length() == 0;
  }
}
