package com.airbridge.core;

import java.util.ArrayList;
import java.util.List;

public final class Manifest {
    public String sessionId;
    public int chunkSize;
    public int totalFiles;
    public long totalBytes;
    public long totalChunks;
    public List<FileEntry> files = new ArrayList<>();

    public static final class FileEntry {
        public int index;
        public String relativePath;
        public long size;
        public String sha256;
        public long startChunk;
        public long chunkCount;
    }
}
