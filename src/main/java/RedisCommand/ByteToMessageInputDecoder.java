package RedisCommand;

import RedisDataBase.RedisString;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import MessageInput.MessageInput;
import io.netty.util.CharsetUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;


// 为什么不适用ReplayingDecoder? 因为它的ByteBuf不是pooled!
public class ByteToMessageInputDecoder extends ByteToMessageDecoder {
    static final int MAX_LEN = 1 << 20;
    private static int STATE = 0;
    private final int REQ = 0;
    private final int TYPE = 1;
    private final int CONTENT = 2;
    static RedisString requestId;
    static RedisString type;
    static int x = 0;
    /*
    * 自定义了简单的checkPoint技术,这样可以利用PooledByteBuf
    * 需要手动来保存和还原readerIndex
    * 注意,由于我们采取类似pipeline的形式,一个channel上会先后处理N个请求的报文,这时肯定存在粘包
    *
    * */
    @Override
    public void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out){
        RedisString content;
        //goCheckpointState(in);
        switch (STATE){
            case REQ:
                in.markReaderIndex();
                requestId = readRedisString(in);
                if(requestId == null){
                    in.resetReaderIndex();
                    return;
                }
                checkPoint(TYPE,in);// 这里不要break,接下去执行
            case TYPE:
                in.markReaderIndex();
                type = readRedisString(in);
                if(type == null){
                    in.resetReaderIndex();return;}
                checkPoint(CONTENT,in);// 这里不要break,接下去执行
            case CONTENT:
                in.markReaderIndex();
                content = readRedisString(in);
                if(content == null){
                    in.resetReaderIndex();
                    return;
                }

                // 这里存在严重bug导致set数据丢失
                out.add(new MessageInput(type, requestId, content));
                // 注意这里要将状态还原
                // 这里没有状态requestId嗷嗷
                checkPoint(REQ,in);// 这里不要break,接下去执行
                break;
        }
    }


    // 用来将String替换成 RedisString
    private RedisString readRedisString(ByteBuf in){
        if(in.readableBytes() < 4){
            return null;
        }

        int len = in.readInt();
        if (len < 0 || len > MAX_LEN) {
            throw new DecoderException("string too long len = " + len);
        }


        if(in.readableBytes() < len){
            return null;
        }else{
            RedisString str = RedisString.allocate(len);
            in.readBytes(str.bytes,0,len);// 将str写入RedisString里面
            str.setSize(len);
            return str;
        }
    }

    private void checkPoint(int state,ByteBuf in){
        STATE = state;
    }







}

