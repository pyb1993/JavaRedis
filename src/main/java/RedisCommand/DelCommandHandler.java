package RedisCommand;

import MessageOutput.MessageOutput;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisObject;
import RedisDataBase.RedisString;
import io.netty.channel.ChannelHandlerContext;

/***处理常见的命令
 *  如果key不存在,返回一个空字符串
 *
 * ***/
public class DelCommandHandler implements RedisCommandHandler<RedisString> {
    static private final RedisString DelConstant = new RedisString("del");
    // 这里是线程安全的,因为异步线程删除只会直接放入removedQueue,不会直接分配出去
    @Override
    public void handle(ChannelHandlerContext ctx, RedisString requestId, RedisString key){
        // 执行 get key 的命令
        RedisDb.del(key);
        ctx.writeAndFlush(new MessageOutput(requestId, DelConstant, ""));
    }
}

