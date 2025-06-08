package com.proxyblob.protocol.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Command {
    NEW((byte) 1),
    ACK((byte) 2),
    DATA((byte) 3),
    CLOSE((byte) 4);

    private final byte code;

    public static Command fromByte(byte code) {
        for (Command cmd : Command.values()) {
            if (cmd.code == code) {
                return cmd;
            }
        }
        return null;
    }
}
