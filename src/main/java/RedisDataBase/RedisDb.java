package RedisDataBase;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;


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
    public static RedisHashMap<String,RedisObject> RedisMap = new RedisHashMap<>();
    public static RedisHashMap<String,Long> ExpiresDict = new RedisHashMap<>();// 用来存储所有过期key的过期时间,方便快速查找/判断/修改
    static final RedisObject[] RedisIntegerPool = new RedisObject[10000];
    static final RedisTimerWheel ExpiresTimer = new RedisTimerWheel();

    static
    {
        // 初始化数字常量池
        for(int i = 0; i < 10000; i++){
            RedisIntegerPool[i] = new RedisObject(RedisObject.REDIS_INTEGER,i);
        }

        // 放一个元素

    }

    // getString
    public static void set(String key,RedisObject val){
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
        if(RedisMap.get(key) != null) {
            ExpiresTimer.enqueue(key, expireDelay);
            ExpiresDict.put(key, RedisTimerWheel.getSystemSeconds() + expireDelay);// 重新设置过期key
        }
    }

    // 只有过期的key才会被移除
    public static void removeIfExipired(String key){
        if(isExpired(key)){
            del(key);
        }
    }

    public static boolean isExpired(String key){
        Long time = ExpiresDict.get(key);
        return (time != null) && time < RedisTimerWheel.getSystemSeconds();
    }

    /*将普通的RedisMap进行切换,转换到能够支持并发的情况 */
    public static void converMapToConcurrent(){
        if(!RedisMap.holdByAnother()) {
            RedisConcurrentHashMap<String,RedisObject> cmap = new RedisConcurrentHashMap<>(RedisMap);
            cmap.incrRef();
            RedisMap = cmap;
            //RedisMap.get("1");
        }
    }

    /*将普通的ExpiresDict进行切换,转换到能够支持并发的情况 */
    public static void convertExpiredToConcurrent(){
        if(!ExpiresDict.holdByAnother()) {
            RedisConcurrentHashMap<String,Long> cmap = new RedisConcurrentHashMap<>(ExpiresDict);
            cmap.incrRef();
            ExpiresDict = cmap;
        }
    }

    /* 只有没有引用了才会将map切换 */
    public static void convertMaptoNormal(){
        RedisMap.decrRef();
        if(!RedisMap.holdByAnother()){
            RedisMap = ((RedisConcurrentHashMap<String, RedisObject>)RedisMap).getMap();
        }
    }

    /*只有没有引用的情况下才会把Expires切换*/
    public static void convertExpiresToNormal(){
        ExpiresDict.decrRef();
        if(!ExpiresDict.holdByAnother()){
            ExpiresDict = ((RedisConcurrentHashMap<String, Long>)ExpiresDict).getMap();
        }
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
    private static RedisObject getIfValid(String key){
        if(isExpired(key)){
            del(key);
            return null;
        }
        return RedisMap.get(key);
    }
}


// 用来处理rehash情况
class RedisHashMap<K,T> extends HashMap<K,T>{
    AtomicInteger refCount;
    Unsafe unsafe;
    Object[] unsafeTable;
    long nextOffset;
    long tableOffset;
    Random rand = new Random();


    public RedisHashMap(){
        super(1);
        super.put(null,null);
        refCount = new AtomicInteger(0);
        try{
            // 通过反射获得对应的Unsafe对象
            Field theUnsafeInstance = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeInstance.setAccessible(true);
            unsafe = (Unsafe) theUnsafeInstance.get(Unsafe.class);


            // 获取hashMap内部的Table
            tableOffset = unsafe.objectFieldOffset(HashMap.class.getDeclaredField("table"));
            unsafeTable = (Object[]) unsafe.getObject(this,tableOffset);
            nextOffset =  unsafe.objectFieldOffset(unsafeTable.getClass().getComponentType().getDeclaredField("next"));//获取next的offset
            super.remove(null);
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    /* 为了使用多态 */
    public void remove(String key){
        super.remove(key);
    }
    public T get(Object key){return super.get(key);}
    public T put(K key, T val){return super.put(key,val);}
    public boolean holdByAnother(){
        return refCount.get() > 0;
    }

    public void incrRef(){
    }

    public void decrRef(){
    }



    // 首先随机获取一个Entry,这个随机不是真随机,是假随机
    // 首先获取一个Node
    // 接下来有一半的几率直接返回该Node,然后0.125概率往前走1-4步
    public Entry random(){
        unsafeTable = (Object[]) unsafe.getObject(this,tableOffset);
        int len = unsafeTable.length;
        Entry e;
        int t = 0;
        do {
            t++;
            int index = rand.nextInt(len);
            e = (Entry) unsafeTable[index];
        }while (t< 25 && e == null);

        int step = rand.nextInt(8);
        if(e == null || step < 4){
            return  e;
        }

        // 那么接下来走 1步或者2步
        Entry next = e;
        step -= 3;
        while (step-- > 0 && (next = getNext(e)) != null){
            e = next;
        }
        return e;
    }

    // 获取该Entry的下一个node
    // 暂时不检查 entry == null
    private Entry getNext(Entry entry){
        return (Entry) unsafe.getObject(entry,nextOffset);
    }


}

/** 一个非常简单的自旋锁 **/
class EasyLock {
    AtomicBoolean lc;

    public EasyLock(){
        lc = new AtomicBoolean();
    }

    public void lock(){
        int num = 1;
        while ((lc.get() == false) && lc.compareAndSet(false, true)) {
            num++;
            if(num % 10 == 0){
                Thread.yield();
            }
        }
    }

    public void unlock(){
        int num = 1;
        while ((lc.get() == true) && lc.compareAndSet(true,false)){
            num++;
            if(num % 10 == 0){
                Thread.yield();
            }
        }
    }
}
/**
 * 支持并发的HashMap
 * 扩容有两种:
 *
 *
 *
 * **/
class RedisConcurrentHashMap<K,T> extends RedisHashMap<K,T>{
    /*下面是成员变量*/
    EasyLock[] lockArr = new EasyLock[256];// 构造的并发锁
    RedisHashMap<K,T> map;

    public RedisConcurrentHashMap(RedisHashMap map){
        this.map = map;
        for(int i = 0; i < 256; i++){
            lockArr[i] = new EasyLock();
        }
    }

    public RedisHashMap<K,T> getMap(){
        return map;
    }

    public void incrRef(){
        refCount.incrementAndGet();
    }

    public void decrRef(){
        refCount.decrementAndGet();
    }

    public void remove(String key){
        lock(key);
        map.remove(key);
        unlock(key);
    }


    public T get(Object key){
        lock(key);
        T ret = map.get(key);
        unlock(key);
        return ret;
    }

    public T put(K key,T val){
        lock(key);
        T old = map.put(key,val);
        unlock(key);
        return old;
    }

    public void lock(Object key){
        getLock(key).lock();
    }

    public void unlock(Object key){
        getLock(key).unlock();
    }

    private EasyLock getLock(Object key){
        int h;
        h = (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
        return lockArr[h & 255];
    }

}

