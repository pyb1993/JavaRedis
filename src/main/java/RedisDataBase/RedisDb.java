package RedisDataBase;

import RedisFuture.RedisFuture;
import RedisServer.RedisServer;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * todo 关于GC的调优
 *      首先cache类型的应用就决定了会有大量的数据存在于老年代,所以把老年代的数据调大是必须的
 *      但是也存在一些问题：
 *          对于某些类型的应用(秒杀,分布式锁等),会导致短时间类有大量对象产生或者消失
 *          这里又分为两种情况：
 *              1 这些对象会持续一段时间(比如20-30分钟,从而进入老年代,然后导致老年代空间不足)
 *              2 这些对象很快就消失:
 *                  那么将导致Minor GC发生的非常频繁
 *
 *          针对情况1:
 *              如果是CMS那么就需要增加参数,在进行remark之前进行一次young GC
 *              如果是G1处理器,那么就不需要进行过多处理
 *          针对情况2:
 *              对象立刻消失的类型
 *              则符合MinorGC的情况,G1 会直接整个young Generation
 *              但是由于Minor GC设置的比较小,所以扫描+复制其实也比较快
 *
 *          另外由于存在对象池,所以针对情况2: 大量的数据进来和删除,这样可以直接复用
 *          针对情况1: 最开始的几十分钟可能存在问题,但是后面由于进入和删除平衡,也会被对象池解决
 *
 *          关于对象池产生的时候,一般来说肯定进入老年代了
 *          这种情况不能轻易的将它释放,即便满足了释放条件而是应该等待至少 T(30min) 以上
 *          由于老年代存活对象比较多,所以拷贝起来会导致比较慢,而且释放的效果也不好
 *          所以应该在晚上定时进行一次GC
 *
 * **/


/**
 * todo 实现手动内存管理,构造对象池
 *      Cache应用,存在时间太长,GC没有价值
 *      转发应用,短时间生成/销毁的数量太多,导致GC压力太大
 *      对于Redis,如果短时间有大量的删除对象的行为,测试结果是目前每0.5s会有40-80ms以上,也就每半s就有一部分命令延迟比较高
 *      一个解决的思路就是使用对象池,这样就可以避免短时间生成大量的进行回收内存,造成GC压力过大
 *      比如分布式锁就会导致GC比较大,另外一部分压力是来自于每次生成的临时对象
 *
 *      但是使用对象池又会导致内存没有办法立刻就被释放,所以一个策略是将删除的元素放入DelayRemovedQueue里面
 *      然后采取定时任务,按周期进行平缓的释放,目的是不让GC过大
 *      但是如果遇到的是那种一瞬间失效,然后很久又不新增的情况,也是无能为力的
 *
 *      由于大部分情况都是String 特别多,所以我们这里需要实现RedisString的对象池
 *      关于RedisString
 *          int len
 *          byte[] str
 *
 *      为了分配对象速度足够快,一个思路是用长度 s sizeForTable来进行定位
 *      然后往小对方向进行搜索找到一个可以满足大小
 *      构造一个 Array[8,16,24,32,48,64,90,128,192,256,384,512,768,1024]
 *      每一个Array的对象都是 一个对象池,这个对象池只分配这么大的RedisString
 *      对象池的构造:
 *          1 初始化, 由于小对象最多, 所以8 - 64 都分配 2048个小对象,128 - 1024分配256个对象,1536-4096分配64个对象
 *          2 然后根据定位的 sizeForTable来获取对应的对象池,如果该对象池没有数据了,那么就往前移动,直到找到满足条件的,最多找1次(比如17就最多找到32，24)
 *          3 如果没有找到,就临时生成一个对象
 *          4 对象池怎么obtain()元素? 很简单,我们将对象池设定为只能单线程操作的,这样就可以用一个固定大小ArrayQueue来构造出来
 *          5 如何针对对象池返回元素.这个也是线程安全的,所以只能有一个线程单独往里面放元素,也就是说,异步线程返回的元素需要想办法同步放回
 *      什么时候使用对象池:
 *          需要进行模拟参数:
 *              规则是: 假设每一个对应大小的slot每1s申请次数等于M,假设每1s释放次数等于N
 *                     令衰减积累等于 S1 = S1 * 0.8 + M, S2 = S2 * 0.8 + N
 *                     这样如果连续TS发现 S1 和 S2很接近,且都大于一个值(太小了对GC的压力并不大,开启不划算)那么就可以开启对象池模式
 *                     然后将申请一个大小为 (S1 + S2)/2 的对象池
 *
 *
 *     什么时候销毁对象池:
 *         如果连续ks都发现规则不满足,那么就应该将这个对象池销毁
 *
 *
 * */
public class RedisDb {
    public static RedisDict<RedisString,RedisObject> RedisMap = new RedisDict<>();
    public static RedisDict<RedisString,Long> ExpiresDict = new RedisDict<>();// 用来存储所有过期key的过期时间,方便快速查找/判断/修改


    static final RedisObject[] RedisIntegerPool = new RedisObject[10000];
    static final RedisTimerWheel ExpiresTimer = new RedisTimerWheel();

    static
    {
        // 初始化数字常量池
        for(int i = 0; i < 10000; i++){
            RedisIntegerPool[i] = new RedisObject(RedisObject.REDIS_INTEGER,i);
        }
    }


    public static boolean set(RedisString key,RedisObject val,boolean nx)
    {
        if(nx){
            if(RedisMap.get(key) == null){
                RedisMap.put(key,val);
                return true;
            }

        }else{
            RedisMap.put(key,val);
            return true;
        }
        return false;
    }


    // 从对应的key获取 object
    // todo key => RedisString
    public static RedisObject get(RedisString key){
        return getIfValid(key);
    }

    // 删除
    // todo 还需要写日志
    // todo 需要考虑对象释放的问题,在remove里面释放value，最后释放key
    public static void del(RedisString key){
        if(key != null) {
            RedisMap.remove(key);
            ExpiresDict.remove(key);
            key.release();
        }
    }

    // 设置超时
    public static void expire(RedisString key,int expireDelay){
        if(getIfValid(key) != null) {
            ExpiresTimer.enqueue(key, expireDelay);
            ExpiresDict.put(key, RedisTimerWheel.getSystemSeconds() + expireDelay);// 重新设置过期key
        }
    }

    // 只有过期的key才会被移除
    public static void removeIfExpired(RedisString key){
        if(isExpired(key)){
            del(key);
        }
    }

    public static boolean isExpired(RedisString key){
        Long time = ExpiresDict.get(key);
        return (time != null) && time < RedisTimerWheel.getSystemSeconds();
    }


    // 清理所有过期的元素
    public static void processExpires(){
        ExpiresTimer.processExpires();
    }

    // 模仿redis hset
    public static void hset(RedisString key, RedisString field, RedisObject val){
        RedisObject h = getIfValid(key);
        if(h == null){
            RedisDict<RedisString,RedisObject> hash = new RedisDict<>();
            hash.put(field,val);
            RedisMap.put(key,RedisObject.redisHashObject(hash));
        }else{
            RedisDict<RedisString,RedisObject> hash = (RedisDict)h.getData();
            hash.put(field,val);
        }
    }

    // 模仿redis hget
    public static RedisObject hget(RedisString key, RedisString field){
        RedisObject h = getIfValid(key);
        if(h != null){
           return ((RedisDict<RedisString,RedisObject>) h.getData()).get(field);
        }
        return null;
    }

    // 构造hyperLogLog
    public static void pfadd(RedisString key, List<String> valList){
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
    public static long pfcount(RedisString key){
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
    private static RedisObject getIfValid(RedisString key){
        if(isExpired(key)){
            del(key);
            return null;
        }
        return RedisMap.get(key);
    }


}