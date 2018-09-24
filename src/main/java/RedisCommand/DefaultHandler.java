package RedisCommand;

import RedisDataBase.RedisString;
import io.netty.channel.ChannelHandlerContext;
import MessageOutput.MessageOutput;

// 有问题的消息统一处理(消息未注册,参数错误等)
public class DefaultHandler implements RedisCommandHandler<String> {
    static final RedisString unknownConstant = new RedisString("unknown type");

    @Override
    public void handle(ChannelHandlerContext ctx, RedisString requestId, String message) {
        ctx.writeAndFlush(new MessageOutput(requestId, unknownConstant, message));
    }
}


