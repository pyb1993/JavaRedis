package RedisCommand;

import Common.Logger;
import Common.RedisStringList;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisObject;
import io.netty.channel.ChannelHandlerContext;
import MessageOutput.MessageOutput;

public class HsetCommandHandler implements RedisCommandHandler<RedisStringList>{
    @Override
    public void handle(ChannelHandlerContext ctx, String requestId, RedisStringList message){
        // 执行 set key value 的命令
        Logger.debug("hset recv :" + message.arr);
        RedisObject val = RedisObject.redisStringObject(message.arr.get(2));
        RedisDb.hset(message.arr.get(0),message.arr.get(1), val);

        ctx.writeAndFlush(new MessageOutput(requestId,"set","1"));
    }
}
