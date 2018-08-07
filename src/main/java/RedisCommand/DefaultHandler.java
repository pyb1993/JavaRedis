package RedisCommand;

import io.netty.channel.ChannelHandlerContext;
import MessageOutput.MessageOutput;

// 有问题的消息统一处理(消息未注册,参数错误等)
public class DefaultHandler implements RedisCommandHandler<String> {
    @Override
    public void handle(ChannelHandlerContext ctx, String requestId,  String message) {
        ctx.writeAndFlush(new MessageOutput(requestId, "UNKNOWN TYPE", message));
    }
}


