package RedisCommand;

import Common.RedisStringList;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisString;
import io.netty.channel.ChannelHandlerContext;
import MessageOutput.MessageOutput;

import java.util.ArrayList;
import java.util.List;

/**
 * 用来实现hyperLogLog命令里面的pfadd命令
 * **/
public class PFCountCommandHandler implements RedisCommandHandler<String> {
    static private final RedisString pfcountConstant = new RedisString("pfcount");

    public void handle(ChannelHandlerContext ctx, RedisString requestId, String key){
        long count = RedisDb.pfcount(new RedisString(key));// todo 优化
        ctx.writeAndFlush(new MessageOutput(requestId,pfcountConstant,count));
    }
}
