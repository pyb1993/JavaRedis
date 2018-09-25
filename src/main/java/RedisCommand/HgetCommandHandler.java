package RedisCommand;

import Common.Logger;
import Common.RedisInputStringPair;
import Common.RedisUtil;
import MessageOutput.MessageOutput;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisObject;
import RedisDataBase.RedisString;
import RedisDataBase.RedisStringPool;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

public class HgetCommandHandler implements RedisCommandHandler<RedisString>  {
    static private final RedisString hgetConstant = new RedisString("hget");

    @Override
    public void handle(ChannelHandlerContext ctx, RedisString requestId, RedisString pair){
        // 执行 set key value 的命令
        // Logger.debug("hget recv :" + message.getFirst() + " " + message.getSecond());
        int len1 = RedisString.readInt(pair,0);
        RedisString key = RedisString.copyRedisString(pair,4,len1);
        int len2 = RedisString.readInt(pair,4 + len1);
        RedisString val = RedisString.copyRedisString(pair,8 + len1,len2);
        RedisObject ret = RedisDb.hget(key,val);
        ctx.writeAndFlush(new MessageOutput(requestId,hgetConstant,ret == null ? "" : ret.getData()));
    }





}
