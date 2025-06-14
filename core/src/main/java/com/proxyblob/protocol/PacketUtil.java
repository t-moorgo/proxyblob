package com.proxyblob.protocol;

import com.proxyblob.protocol.dto.Packet;

import java.nio.ByteBuffer;
import java.util.UUID;

public class PacketUtil {

    public static final byte CmdNew = 1;
    public static final byte CmdAck = 2;
    public static final byte CmdData = 3;
    public static final byte CmdClose = 4;

    public static final int CommandSize = 1;
    public static final int UUIDSize = 16;
    public static final int DataLengthSize = 4;
    public static final int HeaderSize = CommandSize + UUIDSize + DataLengthSize;

    public static byte[] encode(byte command, UUID connectionId, byte[] data) {
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

        return Packet.builder()
                .command(command)
                .connectionId(uuid)
                .data(data)
                .build();
    }
}
