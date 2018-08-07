package RedisDataBase;


import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;


/**
 * 考虑多线程怎么做?
 * 1. 对于 key的整体操作比如 get/del/expire 等, 只需要用ConcurrentHashMap自己的 get/put/del 就可以解决
 * 2. 对于List,Hash,Set,Zset等嵌套结构的操作,我们可以灵活的定义哪些多线程支持,哪些不支持
 *    由于数据结构的不一样,所以需要考虑不同的方法
 *    List(支持多线程):
 *      数据结构: ConCurrentLinkedQueue
 *      比如LPop,RPop这种都使用自带的结构
 *      对于使用的是LIndex,LExist这种需要全局操作的,那么就对整个数据结构直接加全局锁(这种锁应该只阻塞写操作,不阻塞读操作)
 *      具体实现: 每一个RedisObject都有一个自己的状态锁,然后我们考虑这样几种操作:
 *          对 pop,add这种操作,直接使用JDK自带的API即可,但是需要首先获取全局锁,并将锁的状态设置为SINGLE_WRITE(这和SINGLE_READ,GLOBAL_READ，GLOBAL_WRITE冲突)
 *          对 LIndex这种需要遍历的,我们设置状态为GLOABL_READ(这和SINGLE_WRITE冲突,GLOABL_WRITE冲突)
 *          对 LREM(n)这种,设置为GLOBAL_WRITE
 *          对 LINDEX(1)这种 就是SINGLE_READ
 *          复合命令,涉及到多个List的操作(取交集),那么都要加锁,这里要对锁顺序进行排序,避免死锁
 *
 *    Zset(不支持多线程的):
 *      不支持多线程,所以可以直接分配到单线程运行的命令池里面,也就是所有关于Zset相关的命令,我们都分配到唯一的单线程池里面运行
 *      多线程和单线程不支持混合
 *
 *    Blocking:
 *      可以考虑使用一个单独的阻塞线程,其他的worker如果获取锁失败,那么就把它丢到单独的blocking线程里面,然后直接执行下一个任务
 *      如果连续N个任务获取失败,则猜测遇到大量对同一个Object操作的高并发命令,那么考虑在当前线程阻塞执行
 *
 *
 *
 *
 *
 * */
public class RedisDb {
    static final HashMap<String,RedisObject> RedisMap = new HashMap<>(1024);
    static final RedisObject[] RedisIntegerPool = new RedisObject[10000];
    static
    {
        // 初始化数字常量池
        for(int i = 0; i < 10000; i++){
            RedisIntegerPool[i] = new RedisObject(RedisObject.REDIS_INTEGER,i);
        }
    }

    // getString
    public static void set(String key,RedisObject val){
        RedisMap.put(key,val);
    }

    // 从对应的key获取 object
    public static RedisObject get(String key){
        return RedisMap.get(key);
    }

    // 模仿redis hset
    public static void hset(String key, String field, RedisObject val){
        RedisObject h = RedisMap.get(key);
        if(h == null){
            HashMap<String,RedisObject> hash = new HashMap<>();
            hash.put(field,val);
            RedisMap.put(key,RedisObject.redisHashObject(hash));
        }else{
            HashMap<String,RedisObject> hash = (HashMap)h.getData();
            hash.put(field,val);
        }
    }

    // 模仿redis hget
    public static RedisObject hget(String key, String field){
        RedisObject h = RedisMap.get(key);
        if(h != null){
           return ((HashMap<String,RedisObject>) h.getData()).get(field);
        }
        return null;
    }




}
