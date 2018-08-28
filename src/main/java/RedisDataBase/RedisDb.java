package RedisDataBase;

import RedisFuture.RedisFuture;
import RedisServer.RedisServer;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * 1. todo 实现expire机制
 * 2. todo 实现渐进rehash
 *         1 首先对RedisMap进行wrap,使得其变成一个类似对ConcurrentHashMap M
 *         2 然后生成一个新的类似ConCurrentHashMap S,直接锁住对应的index
 *         3 对于任何一个命令, 我们首先在主线程对M照常执行,同时将这个操作插入(set(移除M,set S),del(移除M,移除S))到S里面
 *         4 在异步线程,我们直接对M进行操作,将所有M的元素全部rehash到S里面,意思是将entry从M移除,移动到S
 *         5 关键是如何进行遍历:
 *             这里必须修HashMap的遍历算法,否则就会出现fastfail
 *             首先锁住一个index,然后获取对应的slot的keyset,然后针对这些操作进行reHash(由于hashMap自己的限制,这里不会遍历太长的)
 *             移除M,插入S
 *             最后什么时候结束呢?当整个M为空的时候,reHash就结束了
 *         6 在主线程,立刻也需要同步所有新的操作,比如进行del就对两个都执行,执行set就只对S执行
             如果发现M已经空了,那么就可以结束操作了,将新的S里面的HashMap还原出来即可
 *
 * 3. 实现ConcurrentHashMap的wrap的原理
 *          由于并发竞争的线程不可能太多(目前最多也就3个),所以分段锁可以设置的比较死
 *          将分段锁设置为256个,这样假设3个线程一起并发的抢,完全不碰撞的是(0.99)
 *
 *
 *
 * */
public class RedisDb {
    public static RedisDict<String,RedisObject> RedisMap = new RedisDict<>();
    public static RedisDict<String,Long> ExpiresDict = new RedisDict<>();// 用来存储所有过期key的过期时间,方便快速查找/判断/修改


    static final RedisObject[] RedisIntegerPool = new RedisObject[10000];
    static final RedisTimerWheel ExpiresTimer = new RedisTimerWheel();

    static
    {
        // 初始化数字常量池
        for(int i = 0; i < 10000; i++){
            RedisIntegerPool[i] = new RedisObject(RedisObject.REDIS_INTEGER,i);
        }
    }

    // getString
    // todo 需要写日志
    public static void set(String key,RedisObject val)
    {
        RedisMap.put(key,val);
    }

    // 从对应的key获取 object
    public static RedisObject get(String key){
        return getIfValid(key);
    }

    // 删除
    // todo 还需要写日志
    public static void del(String key){
        RedisMap.remove(key);
        ExpiresDict.remove(key);
    }

    // 设置超时
    public static void expire(String key,int expireDelay){
        if(getIfValid(key) != null) {
            ExpiresTimer.enqueue(key, expireDelay);
            ExpiresDict.put(key, RedisTimerWheel.getSystemSeconds() + expireDelay);// 重新设置过期key
        }
    }

    // 只有过期的key才会被移除
    public static void removeIfExpired(String key){
        if(isExpired(key)){
            del(key);
        }
    }

    public static boolean isExpired(String key){
        Long time = ExpiresDict.get(key);
        return (time != null) && time < RedisTimerWheel.getSystemSeconds();
    }


    // 清理所有过期的元素
    public static void processExpires(){
        ExpiresTimer.processExpires();
    }

    // 模仿redis hset
    public static void hset(String key, String field, RedisObject val){
        RedisObject h = getIfValid(key);
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
        RedisObject h = getIfValid(key);
        if(h != null){
           return ((HashMap<String,RedisObject>) h.getData()).get(field);
        }
        return null;
    }

    // 构造hyperLogLog
    public static void pfadd(String key, List<String> valList){
        RedisObject robj = getIfValid(key);
        HyperLogLog hloglog;
        if(robj == null){
            hloglog = new HyperLogLog();
            RedisMap.put(key,new RedisObject(RedisObject.REDIS_HYPERLOGLOG,hloglog));
        }else{
            hloglog = (HyperLogLog) robj.getData();
        }

        for(String val : valList){
            hloglog.pfadd(val);
        }
    }

    // pfcount命令,获取基数估计
    public static long pfcount(String key){
        RedisObject robj = getIfValid(key);
        if(robj == null){
            return 0;
        }
        HyperLogLog hloglog = (HyperLogLog)robj.getData();
        return hloglog.pfcount();
    }

    /***private module***/
    // 检查是否过期,过期则删除
    /*
    * 线程不安全导致的问题:
    *   0 已经在isExpired里面
    *   1 isExpired返回false
    *   2 接下来直接返回数据
    *   所以根源就是expired字典出现了线程不安全的问题
    *
    *
    * */
    private static RedisObject getIfValid(String key){
        if(isExpired(key)){
            del(key);
            return null;
        }
        return RedisMap.get(key);
    }
}




