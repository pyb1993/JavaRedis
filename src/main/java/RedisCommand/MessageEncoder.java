package RedisCommand;

import MessageOutput.MessageOutput;
import com.alibaba.fastjson.JSON;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.util.CharsetUtil;

import java.util.List;

/*
* 这个类并不涉及状态,所以其实可以共享
* 用来将输出的结果进行编码
* */
public class MessageEncoder extends MessageToMessageEncoder<MessageOutput> {

    @Override
    protected void encode(ChannelHandlerContext ctx, MessageOutput msg, List<Object> out) throws Exception {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer();
        writeStr(buf, msg.getRequestId());
        writeStr(buf, msg.getType());
        writeStr(buf, JSON.toJSONString(msg.getPayload()));
        out.add(buf);
    }

    private void writeStr(ByteBuf buf, String s) {
        int len  = s.getBytes(CharsetUtil.UTF_8).length;
        buf.writeInt(len);
        buf.writeBytes(s.getBytes(CharsetUtil.UTF_8));
    }
}


