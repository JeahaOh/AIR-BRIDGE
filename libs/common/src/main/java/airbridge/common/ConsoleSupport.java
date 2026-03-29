package airbridge.common;

import java.util.Arrays;

public final class ConsoleSupport {
    private ConsoleSupport() {
    }

    public static void printLine(char c, int length) {
        System.out.println(line(c, length));
    }

    public static String line(char c, int length) {
        return repeat(c, length);
    }

    private static String repeat(char c, int count) {
        char[] arr = new char[count];
        Arrays.fill(arr, c);
        return new String(arr);
    }
}
