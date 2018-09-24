package MessageInput;

import RedisDataBase.RedisString;
import com.alibaba.fastjson.JSON;

/*
 用来处理该RPC框架的统一输入格式
 首先需要将获取的Byte转换成MessageInput类型的格式
 payload 一定是一个json格式的参数,利用fastJson直接转换成对应的类
 */
public class MessageInput{
    private RedisString type;
    private RedisString requestId;
    private RedisString payload;

    public MessageInput(RedisString type, RedisString requestId, RedisString payload) {
        this.type = type;
        this.requestId = requestId;
        this.payload = payload;
    }

    public RedisString getType() {
        return type;
    }

    public RedisString getRequestId() {
        return requestId;
    }

    // 将payload从json string => clazz
    public <T> T getPayload(Class<T> clazz) {
        if (payload == null) {
            return null;
        }

        return JSON.parseObject(payload.toString(), clazz);
    }

    public RedisString getContent(){
        return payload;
    }
}
