package RedisCommand;

import Common.Logger;
import Common.RedisStringList;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisObject;
import RedisDataBase.RedisString;
import io.netty.channel.ChannelHandlerContext;
import MessageOutput.MessageOutput;

public class HsetCommandHandler implements RedisCommandHandler<RedisStringList>{
    static private final RedisString hsetConstant = new RedisString("hset");

    @Override
    public void handle(ChannelHandlerContext ctx, RedisString requestId, RedisStringList message){
        // 执行 set key value 的命令
        Logger.debug("hset recv :" + message.arr);
        RedisObject val = RedisObject.redisStringObject(new RedisString(message.arr.get(2)));
        RedisDb.hset(new RedisString(message.arr.get(0)),new RedisString(message.arr.get(1)), val);
        ctx.writeAndFlush(new MessageOutput(requestId,hsetConstant,"1"));
    }
}
