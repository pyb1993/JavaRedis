package RedisCommand;

import Common.RedisStringList;
import RedisDataBase.RedisDb;
import io.netty.channel.ChannelHandlerContext;
import MessageOutput.MessageOutput;

import java.util.ArrayList;
import java.util.List;

/**
 * 用来实现hyperLogLog命令里面的pfadd命令
 * **/
public class PFCountCommandHandler implements RedisCommandHandler<String> {
    public void handle(ChannelHandlerContext ctx, String requestId, String key){
        long count = RedisDb.pfcount(key);// todo 优化
        ctx.writeAndFlush(new MessageOutput(requestId,"pfcount",count));
    }
}
