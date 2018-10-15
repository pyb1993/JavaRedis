package RedisCommand;

import Common.Logger;
import MessageOutput.MessageOutput;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisObject;
import RedisDataBase.RedisString;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

/***处理常见的命令
 *  如果key不存在,返回一个空字符串
 *
 * ***/
public class GetCommandHandler implements RedisCommandHandler<RedisString> {
    static private final RedisString getConstant = new RedisString("get");
    // 这里是线程安全的,因为异步线程删除只会直接放入removedQueue,不会直接分配出去
    @Override
    public void handle(ChannelHandlerContext ctx, RedisString requestId, RedisString key){
        // 执行 get key 的命令
        RedisObject ret = RedisDb.get(key);
        key.release();// 不会导致双重release,因为这个key没有被其它任何地方引用
        if(ret != null) {
            //Logger.debug(requestId + " " + ctx.channel() + ":get resp " + (String) ret.getData());
        }
        ctx.writeAndFlush(new MessageOutput(requestId, getConstant, ret == null ? "" : ret.getData()));
    }
}

