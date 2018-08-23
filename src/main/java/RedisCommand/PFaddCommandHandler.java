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
public class PFaddCommandHandler implements RedisCommandHandler<RedisStringList> {


    public void handle(ChannelHandlerContext ctx, String requestId, RedisStringList message){
        ArrayList<String> a = message.getArr();
        List<String> values = a.subList(1,a.size());
        String key = a.get(0);
        RedisDb.pfadd(key,values);// todo 优化
        ctx.writeAndFlush(new MessageOutput(requestId,"pfadd",""));
    }
}
