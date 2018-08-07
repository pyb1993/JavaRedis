import Common.Logger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.util.CharsetUtil;
import java.io.File;
import java.io.FileInputStream;

public class EchoServerHandler extends ChannelInboundHandlerAdapter {

    // 将会在有数据到达的时候调用
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        File file = new File("/Users/pyb/Documents/test.txt");
        FileInputStream fs = new FileInputStream(file);
        while(fs.available() > 0) {
            ByteBuf byteBuf = ctx.alloc().heapBuffer(100);
            byteBuf.writeBytes(fs,100);
            System.out.println(fs.available());
            ctx.write(byteBuf);
        }// 实际上释放了msg的内存
    }

    // 本次消息读完的时候会启动这个逻辑,应该是相当于read block
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        System.out.println("read complete ");
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }
}

