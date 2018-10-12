package RedisCommand;

import MessageOutput.MessageOutput;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisObject;
import RedisDataBase.RedisString;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;

/***处理常见的命令
 *  如果key不存在,返回一个空字符串
 *
 * ***/
public class TestCommandHandler extends ChannelInboundHandlerAdapter {
    static int allBytes = 0;
    static int times = 0;
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg){
        // 执行 get key 的命令
        ByteBuf in = (ByteBuf)msg;
        times++;
        allBytes += in.readableBytes();
        ctx.fireChannelRead(msg);
        if(allBytes >= 10000000 ){
            System.out.println("共接收:" + allBytes + " byte");
        }
    }
}

