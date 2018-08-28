package RedisCommand;

import Common.Logger;
import Common.RedisInputStringPair;
import MessageOutput.MessageOutput;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisObject;
import io.netty.channel.ChannelHandlerContext;

/** Expire命令需要覆盖原来的过期时间 **/
public class ExpireHandler implements RedisCommandHandler<RedisInputStringPair> {
    @Override
    public void handle(ChannelHandlerContext ctx, String requestId, RedisInputStringPair pair){
        // 执行 get key 的命令
        String key = pair.getFirst();
        String delay = pair.getSecond();
        //RedisObject ret = RedisDb.get(key);
        RedisDb.expire(key,Integer.parseInt(delay));
        Logger.debug(requestId + " " + ctx.channel() + ":send expire ok");

        ctx.writeAndFlush(new MessageOutput(requestId,"expire","ok"));
    }
}

