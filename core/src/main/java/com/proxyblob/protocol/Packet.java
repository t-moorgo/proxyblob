package com.proxyblob.protocol;

import lombok.Getter;

import java.nio.ByteBuffer;
import java.util.UUID;

@Getter
public class Packet {

    // Command types
    public static final byte CmdNew = 1;
    public static final byte CmdAck = 2;
    public static final byte CmdData = 3;
    public static final byte CmdClose = 4;

    // Field sizes
    public static final int CommandSize = 1;
    public static final int UUIDSize = 16;
    public static final int DataLengthSize = 4;
    public static final int HeaderSize = CommandSize + UUIDSize + DataLengthSize;

    private final byte command;
    private final UUID connectionId;
    private final byte[] data;

    public Packet(byte command, UUID connectionId, byte[] data) {
        this.command = command;
        this.connectionId = connectionId;
        this.data = data != null ? data : new byte[0];
    }

    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(HeaderSize + data.length);
        buffer.put(command);
        buffer.putLong(connectionId.getMostSignificantBits());
        buffer.putLong(connectionId.getLeastSignificantBits());
        buffer.putInt(data.length);
        if (data.length > 0) {
            buffer.put(data);
        }
        return buffer.array();
    }

    public static Packet decode(byte[] bytes) {
        if (bytes == null || bytes.length < HeaderSize) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        byte command = buffer.get();
        if (command < CmdNew || command > CmdClose) {
            return null;
        }

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
