package com.airbridge.frame;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.CRC32;

public final class FramePacket {
    public static final int MAGIC = 0x41425232; // ABR2
    public static final byte VERSION = 1;

    public enum Type {
        META,
        DATA
    }

    private final Type type;
    private final String sessionId;
    private final int frameNumber;
    private final int fileIndex;
    private final int fileChunkIndex;
    private final int fileChunkCount;
    private final int metaShardIndex;
    private final int metaShardCount;
    private final byte[] payload;

    private FramePacket(
            Type type,
            String sessionId,
            int frameNumber,
            int fileIndex,
            int fileChunkIndex,
            int fileChunkCount,
            int metaShardIndex,
            int metaShardCount,
            byte[] payload
    ) {
        this.type = type;
        this.sessionId = sessionId;
        this.frameNumber = frameNumber;
        this.fileIndex = fileIndex;
        this.fileChunkIndex = fileChunkIndex;
        this.fileChunkCount = fileChunkCount;
        this.metaShardIndex = metaShardIndex;
        this.metaShardCount = metaShardCount;
        this.payload = payload;
    }

    public static FramePacket meta(String sessionId, int frameNumber, int shardIndex, int shardCount, byte[] payload) {
        return new FramePacket(Type.META, sessionId, frameNumber, -1, -1, -1, shardIndex, shardCount, payload);
    }

    public static FramePacket data(
            String sessionId,
            int frameNumber,
            int fileIndex,
            int fileChunkIndex,
            int fileChunkCount,
            byte[] payload
    ) {
        return new FramePacket(Type.DATA, sessionId, frameNumber, fileIndex, fileChunkIndex, fileChunkCount, -1, -1, payload);
    }

    public Type type() {
        return type;
    }

    public String sessionId() {
        return sessionId;
    }

    public int frameNumber() {
        return frameNumber;
    }

    public int fileIndex() {
        return fileIndex;
    }

    public int fileChunkIndex() {
        return fileChunkIndex;
    }

    public int fileChunkCount() {
        return fileChunkCount;
    }

    public int metaShardIndex() {
        return metaShardIndex;
    }

    public int metaShardCount() {
        return metaShardCount;
    }

    public byte[] payload() {
        return payload;
    }

    public byte[] toBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeByte(type == Type.META ? 1 : 2);
            out.writeUTF(sessionId);
            out.writeInt(frameNumber);
            out.writeInt(fileIndex);
            out.writeInt(fileChunkIndex);
            out.writeInt(fileChunkCount);
            out.writeInt(metaShardIndex);
            out.writeInt(metaShardCount);
            out.writeInt(payload.length);
            out.writeInt(crc32(payload));
            out.write(payload);
        }
        return baos.toByteArray();
    }

    public static Optional<FramePacket> fromBytes(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != MAGIC) {
                return Optional.empty();
            }
            if (in.readUnsignedByte() != VERSION) {
                return Optional.empty();
            }

            int rawType = in.readUnsignedByte();
            Type type;
            if (rawType == 1) {
                type = Type.META;
            } else if (rawType == 2) {
                type = Type.DATA;
            } else {
                return Optional.empty();
            }

            String sessionId = in.readUTF();
            int frameNumber = in.readInt();
            int fileIndex = in.readInt();
            int fileChunkIndex = in.readInt();
            int fileChunkCount = in.readInt();
            int metaShardIndex = in.readInt();
            int metaShardCount = in.readInt();
            int payloadLength = in.readInt();
            int payloadCrc = in.readInt();

            if (payloadLength < 0 || payloadLength > data.length) {
                return Optional.empty();
            }

            byte[] payload = in.readNBytes(payloadLength);
            if (payload.length != payloadLength) {
                return Optional.empty();
            }
            if (crc32(payload) != payloadCrc) {
                return Optional.empty();
            }

            return Optional.of(new FramePacket(
                    type,
                    sessionId,
                    frameNumber,
                    fileIndex,
                    fileChunkIndex,
                    fileChunkCount,
                    metaShardIndex,
                    metaShardCount,
                    payload
            ));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static int estimateHeaderSizeBytes(String sessionId) {
        return 40 + sessionId.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int crc32(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload);
        return (int) crc.getValue();
    }
}
