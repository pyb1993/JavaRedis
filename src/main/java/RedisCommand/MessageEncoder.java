package RedisCommand;

import CommandDispatcher.CommandDispatcher;
import MessageOutput.MessageOutput;
import RedisDataBase.AbstractPooledObject;
import RedisDataBase.RedisString;
import com.alibaba.fastjson.JSON;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.util.CharsetUtil;

import java.nio.ByteBuffer;
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
        Object payload = msg.getPayload();
        if(CommandDispatcher.newProtocal(msg.getType())){
            // 使用自定义协议直接传递,让客户端自己去解析
            if(payload instanceof AbstractPooledObject){
                writeStr(buf, (RedisString) payload);
            }else{
                writeStr(buf, (String) payload);
            }
        }else{
            // 使用json作为对象传递
            writeStr(buf, JSON.toJSONString(payload));
        }
        out.add(buf);

        // 在这里可以开始进行释放 requestId可以立刻释放的，type是static,不能释放

        msg.getRequestId().release();
        // payload能否释放要根据命令来,所以需要在各个handler里面进行释放,这里不能统一释放
    }

    private void writeStr(ByteBuf buf, String s) {
        int len  = s.getBytes(CharsetUtil.UTF_8).length;
        buf.writeInt(len);
        buf.writeBytes(s.getBytes(CharsetUtil.UTF_8));
    }

    // 注意这里不能全部写入
    private void writeStr(ByteBuf buf, RedisString s) {
        int len = s.size();
        buf.writeInt(len);
        if(len > 0) {
            buf.writeBytes(s.bytes, 0, s.size());
        }
    }
}


