package RedisCommand;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

public class outHandler extends ChannelOutboundHandlerAdapter {
    @Override
    public final void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise){
        System.out.println("你好");
    }
}
