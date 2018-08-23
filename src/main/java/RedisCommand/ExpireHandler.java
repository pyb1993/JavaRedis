package RedisCommand;

import Common.Logger;
import Common.RedisInputStringPair;
import MessageOutput.MessageOutput;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisObject;
import io.netty.channel.ChannelHandlerContext;

public class ExpireHandler implements RedisCommandHandler<RedisInputStringPair> {
    @Override
    public void handle(ChannelHandlerContext ctx, String requestId, RedisInputStringPair pair){
        // 执行 get key 的命令
        String key = pair.getFirst();
        String delay = pair.getSecond();
        RedisObject ret = RedisDb.get(key);
        if(ret != null) {
            RedisDb.expire(key,Integer.parseInt(delay));
            Logger.debug(requestId + " " + ctx.channel() + ":send expire ok");
        }else{
           // System.out.println(key + " expire not exist");
        }

        ctx.writeAndFlush(new MessageOutput(requestId,"expire","ok"));
    }
}

