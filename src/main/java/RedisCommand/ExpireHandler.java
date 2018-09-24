package RedisCommand;

import Common.Logger;
import Common.RedisInputStringPair;
import MessageOutput.MessageOutput;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisObject;
import RedisDataBase.RedisString;
import io.netty.channel.ChannelHandlerContext;

/** Expire命令需要覆盖原来的过期时间 **/
public class ExpireHandler implements RedisCommandHandler<RedisInputStringPair> {
    static private final RedisString expireConstant = new RedisString("expire");

    @Override
    public void handle(ChannelHandlerContext ctx, RedisString requestId, RedisInputStringPair pair){
        // 执行 get key 的命令
        String key = pair.getFirst();
        String delay = pair.getSecond();
        //RedisObject ret = RedisDb.get(key);
        RedisDb.expire(new RedisString(key),Integer.parseInt(delay));
        Logger.debug(requestId + " " + ctx.channel() + ":send expire ok");

        ctx.writeAndFlush(new MessageOutput(requestId,expireConstant,"ok"));
    }
}

