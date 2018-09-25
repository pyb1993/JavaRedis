package RedisCommand;

import Common.Logger;
import Common.RedisInputStringPair;
import Common.RedisUtil;
import MessageOutput.MessageOutput;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisObject;
import RedisDataBase.RedisString;
import io.netty.channel.ChannelHandlerContext;

/** Expire命令需要覆盖原来的过期时间 **/
public class ExpireHandler implements RedisCommandHandler<RedisString> {
    static private final RedisString expireConstant = new RedisString("expire");

    @Override
    public void handle(ChannelHandlerContext ctx, RedisString requestId, RedisString pair){
        // 执行 get key 的命令
        int len1 = RedisString.readInt(pair,0);
        RedisString key = RedisString.copyRedisString(pair,4,len1);
        int len2 = RedisString.readInt(pair,4 + len1);
        RedisString delay = RedisString.copyRedisString(pair,8 + len1,len2);
        pair.release();

        //RedisObject ret = RedisDb.get(key);
        RedisDb.expire(key,RedisUtil.parseInt(delay));
        Logger.debug(requestId + " " + ctx.channel() + ":send expire ok");

        ctx.writeAndFlush(new MessageOutput(requestId,expireConstant,"ok"));
    }
}

