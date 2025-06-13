package com.proxyblob.protocol;

import com.proxyblob.protocol.dto.Packet;
import lombok.experimental.UtilityClass;

import java.nio.ByteBuffer;
import java.util.UUID;

@UtilityClass
public class PacketUtil {

    public final byte CmdNew = 1;
    public final byte CmdAck = 2;
    public final byte CmdData = 3;
    public final byte CmdClose = 4;

    public final int CommandSize = 1;
    public final int UUIDSize = 16;
    public final int DataLengthSize = 4;
    public final int HeaderSize = CommandSize + UUIDSize + DataLengthSize;

    public byte[] encode(byte command, UUID connectionId, byte[] data) {
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

    public Packet decode(byte[] bytes) {
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
