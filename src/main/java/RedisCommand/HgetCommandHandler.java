package RedisCommand;

import Common.Logger;
import Common.RedisInputStringPair;
import MessageOutput.MessageOutput;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisObject;
import io.netty.channel.ChannelHandlerContext;

public class HgetCommandHandler implements RedisCommandHandler<RedisInputStringPair>  {
    @Override
    public void handle(ChannelHandlerContext ctx, String requestId, RedisInputStringPair message){
        // 执行 set key value 的命令
        Logger.debug("hget recv :" + message.getFirst() + " " + message.getSecond());
        RedisObject ret = RedisDb.hget(message.getFirst(), message.getSecond());
        Logger.debug(ret == null ? "true" : "false");
        ctx.writeAndFlush(new MessageOutput(requestId,"set",ret == null ? "" : ret.getData()));
    }

}
