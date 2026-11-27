package com.stsvita.compat;

import java.util.ArrayList;

/** String.split with OpenJDK 8's one-character fast path; TeaVM always compiles a regex. */
public final class FastSplit {

    public static String[] split(String s, String regex) {
        return split(s, regex, 0);
    }

    public static String[] split(String s, String regex, int limit) {
        char ch = 0;
        if (((regex.length() == 1 && ".$|()[{^?*+\\".indexOf(ch = regex.charAt(0)) == -1)
                || (regex.length() == 2 && regex.charAt(0) == '\\'
                        && (((ch = regex.charAt(1)) - '0') | ('9' - ch)) < 0
                        && ((ch - 'a') | ('z' - ch)) < 0
                        && ((ch - 'A') | ('Z' - ch)) < 0))
                && (ch < Character.MIN_HIGH_SURROGATE || ch > Character.MAX_LOW_SURROGATE)) {
            int off = 0;
            int next;
            boolean limited = limit > 0;
            ArrayList<String> list = new ArrayList<>();
            while ((next = s.indexOf(ch, off)) != -1) {
                if (!limited || list.size() < limit - 1) {
                    list.add(s.substring(off, next));
                    off = next + 1;
                } else {
                    int last = s.length();
                    list.add(s.substring(off, last));
                    off = last;
                    break;
                }
            }
            if (off == 0) {
                return new String[] {s};
            }
            if (!limited || list.size() < limit) {
                list.add(s.substring(off, s.length()));
            }
            int resultSize = list.size();
            if (limit == 0) {
                while (resultSize > 0 && list.get(resultSize - 1).isEmpty()) {
                    resultSize--;
                }
            }
            return list.subList(0, resultSize).toArray(new String[resultSize]);
        }
        return s.split(regex, limit);
    }

    private FastSplit() {}
}
