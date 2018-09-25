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
public class PFaddCommandHandler implements RedisCommandHandler<RedisStringList> {
    static private final RedisString pfaddConstant = new RedisString("pfadd");


    public void handle(ChannelHandlerContext ctx, RedisString requestId, RedisStringList message){
        ArrayList<String> a = message.getArr();
        List<String> values = a.subList(1,a.size());
        String key = a.get(0);
        RedisDb.pfadd(RedisString.allocate(key),values);// todo 优化
        ctx.writeAndFlush(new MessageOutput(requestId,pfaddConstant,""));
    }
}
