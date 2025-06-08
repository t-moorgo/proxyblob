package com.proxyblob.protocol.model;

import lombok.Getter;

import java.nio.ByteBuffer;
import java.util.UUID;

@Getter
public class Packet {

    public static final int COMMAND_SIZE = 1;
    public static final int UUID_SIZE = 16;
    public static final int LENGTH_SIZE = 4;
    public static final int HEADER_SIZE = COMMAND_SIZE + UUID_SIZE + LENGTH_SIZE;

    private final UUID connectionId;
    private final byte command;
    private final byte[] data;

    public Packet(byte command, UUID connectionId, byte[] data) {
        this.command = command;
        this.connectionId = connectionId;
        this.data = data != null ? data : new byte[0];
    }

    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + data.length);
        buffer.put(command);
        buffer.putLong(connectionId.getMostSignificantBits());
        buffer.putLong(connectionId.getLeastSignificantBits());
        buffer.putInt(data.length);
        buffer.put(data);
        return buffer.array();
    }

    public static Packet decode(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER_SIZE) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        byte command = buffer.get();
        long msb = buffer.getLong();
        long lsb = buffer.getLong();
        UUID uuid = new UUID(msb, lsb);

        int length = buffer.getInt();
        if (buffer.remaining() != length) {
            return null;
        }

        byte[] data = new byte[length];
        buffer.get(data);

        return new Packet(command, uuid, data);
    }
}
