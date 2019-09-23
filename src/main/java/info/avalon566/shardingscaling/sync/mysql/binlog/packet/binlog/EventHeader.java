package info.avalon566.shardingscaling.sync.mysql.binlog.packet.binlog;

import info.avalon566.shardingscaling.sync.mysql.binlog.codec.DataTypesCodec;
import io.netty.buffer.ByteBuf;
import lombok.Data;

/**
 * @author avalon566
 * https://github.com/mysql/mysql-server/blob/5.7/sql/log_event.h
 * +---------+---------+---------+------------+-----------+-------+
 * |timestamp|type code|server_id|event_length|end_log_pos|flags  |
 * |4 bytes  |1 byte   |4 bytes  |4 bytes     |4 bytes    |2 bytes|
 * +---------+---------+---------+------------+-----------+-------+
 */
@Data
public class EventHeader {
    private int timeStamp;
    private byte typeCode;
    private int serverId;
    private int eventLength;
    private int endLogPos;
    private short flags;

    public void fromBytes(ByteBuf data) {
        timeStamp = DataTypesCodec.readInt(data);
        typeCode = DataTypesCodec.readByte(data);
        serverId = DataTypesCodec.readInt(data);
        eventLength = DataTypesCodec.readInt(data);
        endLogPos = DataTypesCodec.readInt(data);
        flags = DataTypesCodec.readShort(data);
    }
}
