package RedisCommand;


import Common.Logger;
import RedisDataBase.RedisString;
import com.alibaba.fastjson.JSON;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import MessageInput.MessageInput;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.util.CharsetUtil;

import java.nio.ByteBuffer;
import java.util.List;

/* ReplayingDecoder, 继承BytesToMessageDecoder
   相对于普通的Decoder来说,关键在于不需要去判断Byte的数量是不是足够的
   该方法重新实现了一种ByteBuf ReplayingDecoderByteBuf, 会在调用ReadInt等方法的时候进行检查
   如果不满足规定的字数,就要抛出异常,然后callDecode方法也重写了,会接住这个异常并且重置读取的指针位置

   @format 统一的字符串格式是 len + 字符串
 */
public class MessageDecoder extends ReplayingDecoder<MessageInput> {
    static final int MAX_LEN = 1 << 20;

    // todo 这里的String可以池化
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Logger.debug("recieve data");
        RedisString requestId = readRedisString(in);
        RedisString type = readRedisString(in);
        RedisString content = readRedisString(in);
        out.add(new MessageInput(type, requestId, content));
    }


    // 用来将String替换成 RedisString
    private RedisString readRedisString(ByteBuf in){
        int len = in.readInt();
        if (len < 0 || len > MAX_LEN) {
            throw new DecoderException("string too long len = " + len);
        }

        RedisString str = RedisString.allocate(len);
        in.readBytes(str.bytes,0,len);// 将str写入RedisString里面
        str.setSize(len);
        return str;
    }
}

