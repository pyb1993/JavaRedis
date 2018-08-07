package RedisCommand;

import Common.Logger;
import Common.RedisInputStringPair;
import MessageOutput.MessageOutput;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisObject;
import io.netty.channel.ChannelHandlerContext;

/*** 处理常见的命令 ***/
public class SetCommandHandler implements RedisCommandHandler<RedisInputStringPair> {
    @Override
    public void handle(ChannelHandlerContext ctx, String requestId, RedisInputStringPair message){
        // 执行 set key value 的命令
        Logger.debug("set recv :" + message.getSecond());
        RedisDb.set(message.getFirst(),
                RedisObject.redisStringObject(message.getSecond()));

        ctx.writeAndFlush(new MessageOutput(requestId,"set","set ok " + message.getSecond()));
    }
}

