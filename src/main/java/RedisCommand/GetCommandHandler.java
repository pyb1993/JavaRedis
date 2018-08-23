package RedisCommand;

import Common.Logger;
import MessageOutput.MessageOutput;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisObject;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

/***处理常见的命令
 *  如果key不存在,返回一个空字符串
 *
 * ***/
public class GetCommandHandler implements RedisCommandHandler<String> {
    @Override
    public void handle(ChannelHandlerContext ctx, String requestId, String key){
        // 执行 get key 的命令
        RedisObject ret = RedisDb.get(key);
        if(ret != null) {
            Logger.debug(requestId + " " + ctx.channel() + ":get resp " + (String) ret.getData());
        }
        ctx.writeAndFlush(new MessageOutput(requestId, "get", ret == null ? "" : ret.getData()));
    }
}

