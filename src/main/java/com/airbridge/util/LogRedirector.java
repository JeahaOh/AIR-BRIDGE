package com.airbridge.util;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class LogRedirector implements AutoCloseable {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final PrintStream originalOut;
    private final PrintStream originalErr;
    private final PrintStream logStream;
    private final PrintStream teeOut;
    private final PrintStream teeErr;

    private LogRedirector(PrintStream originalOut, PrintStream originalErr, PrintStream logStream) {
        this.originalOut = originalOut;
        this.originalErr = originalErr;
        this.logStream = logStream;
        this.teeOut = new TeePrintStream(originalOut, logStream);
        this.teeErr = new TeePrintStream(originalErr, logStream);
    }

    public static LogRedirector start(String prefix) throws IOException {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String filename = prefix + "-" + timestamp + ".out";
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path logPath = dir.resolve(filename);
        PrintStream logStream = new PrintStream(new FileOutputStream(logPath.toFile(), true), true, java.nio.charset.StandardCharsets.UTF_8);

        LogRedirector redirector = new LogRedirector(System.out, System.err, logStream);
        System.setOut(redirector.teeOut);
        System.setErr(redirector.teeErr);
        System.out.println("[log] writing output to " + logPath);
        return redirector;
    }

    @Override
    public void close() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        try {
            logStream.flush();
        } finally {
            logStream.close();
        }
    }

    private static final class TeePrintStream extends PrintStream {
        private final PrintStream a;
        private final PrintStream b;

        private TeePrintStream(PrintStream a, PrintStream b) {
            super(a);
            this.a = a;
            this.b = b;
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            a.write(buf, off, len);
            b.write(buf, off, len);
        }

        @Override
        public void flush() {
            a.flush();
            b.flush();
        }
    }
}
