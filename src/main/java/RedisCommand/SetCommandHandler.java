package RedisCommand;

import Common.Logger;
import Common.RedisInputStringPair;
import MessageOutput.MessageOutput;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisObject;
import io.netty.channel.ChannelHandlerContext;

/*** 处理常见的命令 ***/
public class SetCommandHandler implements RedisCommandHandler<RedisInputStringPair> {

    // todo 这里需要进行池化
    @Override
    public void handle(ChannelHandlerContext ctx, String requestId, RedisInputStringPair message){
        // 执行 set key value 的命令
        // Logger.debug(requestId + " " + ctx.channel() + ": set recv :" + message.getSecond());
        // todo 1. new RedisString
        // todo 2. 将RedisInputStringPair 的类型修改一下,直接使用RedisString(需要抛弃 FastJson)
        // todo 3. 注意需要在里面释放
        RedisDb.set(message.getFirst(),
                RedisObject.redisStringObject(message.getSecond()));

        ctx.writeAndFlush(new MessageOutput(requestId,"set",""));
    }
}

