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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/* ReplayingDecoder, 继承BytesToMessageDecoder
   相对于普通的Decoder来说,关键在于不需要去判断Byte的数量是不是足够的
   该方法重新实现了一种ByteBuf ReplayingDecoderByteBuf, 会在调用ReadInt等方法的时候进行检查
   如果不满足规定的字数,就要抛出异常,然后callDecode方法也重写了,会接住这个异常并且重置读取的指针位置

   @format 统一的字符串格式是 len + 字符串
 */
public class MessageDecoder extends ReplayingDecoder<MessageInput> {
    static final int MAX_LEN = 1 << 20;
    static int y = 0;
    static boolean flag = false;
    static final Set<String> entry = new HashSet<>();
    static final Set<String> lost = new HashSet<>();
    static final HashMap<String,String> map = new HashMap<>();
    static int k = 0;
    static int rep = 0;
    String last = "";

    // todo 这里的String可以池化
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        //Logger.debug("recieve data");

        // 首先可以肯定是有些数据没有传到这里导致的; 就是有一部分数据没有成功传过来

        RedisString requestId;
        // 记录获取requestId失败的那些残包
        try{
             requestId = readRedisString(in,true);
        }catch (Throwable e){
            lost.add(last = " " + k++);// 记录一个requestId
            throw e;
        }

        // 这一步移除正常的残包,剩下的就全部都是「最终未执行的」
        if(!last.isEmpty()){
            lost.remove(last);// 这样剩下的就全部是卡在前面没有前进的包的request
            last = "";
        }
        entry.add(requestId.toString());// entry代表完整的requestId的解析
        RedisString type = readRedisString(in,false);// 注意,需要释放type!!!!!!!!!
        RedisString content = readRedisString(in,false);
        out.add(new MessageInput(type, requestId, content));


        y++;// y代表完整的解析
        if(y > 39900){
            System.out.println("y=" + y);
            System.out.println("size =" + entry.size());
            System.out.println("lost_pre = " + lost.size());
        }

    }


    // 用来将String替换成 RedisString
    private RedisString readRedisString(ByteBuf in,boolean flag) {
        int len;
        if(flag){
            len = 8;
        }else{
            len = in.readInt();
        }

        if (len < 0 || len > MAX_LEN) {
            throw new DecoderException("string too long len = " + len);
        }

        RedisString str = RedisString.allocate(len);
        in.readBytes(str.bytes, 0, len);// 将str写入RedisString里面
        str.setSize(len);
        return str;

    }
}

