package MessageRegister;

import Common.RedisInputStringPair;
import Common.RedisStringList;
import RedisCommand.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/*  用来映射 type => handler
    用来映射 type => inputType
*/

public class MessageRegister {
    private static final Map<String, Class<?>> clazzMapping = new HashMap<>();
    private static final Map<String, RedisCommandHandler<?>> handlerMapping = new HashMap<>();
    private static final MessageRegister sigleton = new MessageRegister();
    public static final DefaultHandler defaultHandler = new DefaultHandler();


    public static Class<?> getMessage(String type) {
        return clazzMapping.get(type);
    }

    public static RedisCommandHandler<?> getHandler(String type) {
        return handlerMapping.getOrDefault(type,defaultHandler);
    }

    /** 下面这样写是为了可以进行链式的调用 registerDefault().regster()*.register()*/
    public static MessageRegister registerDefault(){
        return new MessageRegister()
                .register("set", RedisInputStringPair.class,new SetCommandHandler())
                .register("incr", RedisInputStringPair.class,new SetCommandHandler())
                .register("get", String.class,new GetCommandHandler())
                .register("hset", RedisStringList.class, new HsetCommandHandler())
                .register("hget",RedisInputStringPair.class, new HgetCommandHandler())
                .register("pfadd",RedisStringList.class, new PFaddCommandHandler())
                .register("pfcount", String.class, new PFCountCommandHandler())
                .register("expire", RedisInputStringPair.class, new ExpireHandler());
    }


    public MessageRegister register(String type, Class<?> clazz, RedisCommandHandler<?> handler) {
        if (clazz == null || handler == null) {
            throw new RuntimeException("params cannot be null");
        }
        clazzMapping.put(type, clazz);
        handlerMapping.put(type, handler);
        return this;
    }

}
