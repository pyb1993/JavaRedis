package RedisCommand;

import Common.Logger;
import Common.RedisInputStringPair;
import MessageOutput.MessageOutput;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisObject;
import RedisDataBase.RedisString;
import io.netty.channel.ChannelHandlerContext;

/*** 处理常见的命令 ***/
public class SetCommandHandler implements RedisCommandHandler<RedisString> {
    static private final RedisString setConstant = new RedisString("set");

    // todo 这里需要进行池化
    @Override
    public void handle(ChannelHandlerContext ctx, RedisString requestId, RedisString pair){
        // 执行 set key value 的命令
        // Logger.debug(requestId + " " + ctx.channel() + ": set recv :" + message.getSecond());
        // todo 注意需要在里面释放
        // 目前这里有一个bug,猜测是由于objectpool导致的
        int len1 = RedisString.readInt(pair,0);
        RedisString key = RedisString.copyRedisString(pair,4,len1);
        int len2 = RedisString.readInt(pair,4 + len1);
        RedisString val = RedisString.copyRedisString(pair,8 + len1,len2);
        pair.release();
        RedisDb.set(key, RedisObject.redisStringObject(val));
        // key可能可以释放,val也是可能可以释放
        ctx.writeAndFlush(new MessageOutput(requestId,setConstant,""));


    }
}

