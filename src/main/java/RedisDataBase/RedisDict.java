package RedisDataBase;

import RedisFuture.RedisRunnable;
import RedisFuture.RehashFuture;
import RedisServer.RedisServer;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * RedisDict结构,这一层同时负责 普通状态 -> 并发状态, 渐进rehash 的相关逻辑
 * 对外部透明
 *
 *
 * **/

public class RedisDict<K,T>{
    RedisHashMap<K,T> map;
    volatile RedisConcurrentHashMap<K,T> rehashMap;
    MyReadWriteLock globalLock = new MyReadWriteLock();
    AtomicInteger hold = new AtomicInteger(0);//表示有几个线程持有了这个RedisDict(理论上一定是并发状态,但未必是扩容状态)
    volatile boolean inConcurrent = false;
    volatile long lastRehash = RedisTimerWheel.getSystemTimeMillSeconds();

    public RedisDict(){
        map = new RedisHashMap<>();
    }

    public void incrementHold(){
        hold.incrementAndGet();
    }

    public void decrementHold(){
        hold.decrementAndGet();
    }

    // 注意incrementHold和decrementHold有可能在异步线程里面执行
    public boolean holdByOther(){
        return hold.get() > 0;
    }

    // 开始进行rehash
    // 只能在主线程开始执行
    public void startRehash() {
        if(rehashMap == null){

            // todo 改大一点阈值
            // todotodo 必须修改会这个只
            if(map.size() < 1000000 && !inConcurrent()){
                // 直接执行的开销很小1ms,直接做了
                map.rehash();
                return;
            }

            toConcurrent();
            // 因为只能在主线程执行,所以这里不可能出现状态的变化,这里加锁是为了防止异步线程在执行的时候突然状态改变
            gwriteLock();

            // 首先创建当前length两倍大小的capacity的元素
            RedisHashMap tmp = null;
            if (needGrowth(false)) {// 小心这里造成死锁
                tmp = new RedisHashMap(map.length() * 2);
                System.out.println("开始创建扩容: new_len = " + tmp.length() + "现有size" + map.size() + "attr: " + this + "MAP:" + RedisDb.RedisMap);
            } else if (needtrim(false)) {
                tmp = new RedisHashMap(size());
                System.out.println("开始缩小容量: new_len = " + tmp.length() + "现有size" + map.size() + "attr: " + this + "MAP:" + RedisDb.RedisMap);
            } else {
                assert false;
            }

            rehashMap = new RedisConcurrentHashMap<>(tmp);
            lastRehash = RedisTimerWheel.getSystemTimeMillSeconds();// 这个必须在上面的逻辑之后执行,否则会导致needTrim判断错误
            // 开始提交异步任务

            Future rehashFuture = RedisServer.ExpireHelper
                    .submit(new RedisRunnable(
                            () -> {
                                final int len = map.length();
                                for (int i = 0; i < len; ++i) {
                                   ((RedisConcurrentHashMap) map).reHashOneSlot(i, rehashMap);
                                }
                            })
                    );

            RedisServer.addFuture(new RehashFuture(this, rehashFuture));// 将需要的结果加入
            gwriteUnLock();
        }
    }

    // 因为startRehash 必须立刻执行,所以只能延迟stopRehash

    @Test
    public synchronized void stopRehash() {
        if(rehashMap != null){
            if(map.size() != 0){
                System.out.println("ERROR : " + map.size());
            }
            assert (map.size() == 0);
            assert (!holdByOther());

            gwriteLock();
            map = rehashMap.getMap();//直接回复成普通的map
            rehashMap = null;
            inConcurrent = false;
            gwriteUnLock();
        }
    }

    /*
     * 需要考虑扩容情况下的线程安全问题
     *
     * put可能会在异步线程执行,如果是在主线程执行,那么就不需要rehashMap状态变化的问题
     *
     * */
    public void put(K key, T val) {
        if(RedisServer.isCurrentThread()){
            safePut(key,val);
        }else{
            greadLock();
            if(!inRehashProgress()){
                // 不扩容状态
                safePut(key, val);
            }else{
                // 这里处于扩容状态， rehashMap != null
                // 但是由于没有锁的保护,可能突然变成非扩容状态,rehashMap == NULL
                // 所以 stopRehash只能在「没有异步线程持有dict时候才能执行」
                safePut(key,val);
            }
            greadUnLock();
        }

        // 这里有多种case 1 主线程扩容,当前字典可能是「普通状态」／「并发状态」
        // 2 异步线程扩容,且当前字典已经是扩容的状态
        // 3 异步线程扩容,当前字典一定是并发状态
        // 我们只在主线程切换状态,所以其它两个线程我们不执行
        if(RedisServer.isCurrentThread() && needGrowth(true)){
            startRehash(); // case2会直接被startRehash过滤
        }
    }

    // 在主线程执行,不需要担心状态改变
    void safePut(K key, T val){
        if(inRehashProgress()){
            // 扩容状态
            rehashMap.put(key,val);
            map.remove(key);// 这一步是必要的
        }else {
            // 非扩容状态
            map.put(key,val);
        }
    }

    /* 需要保证线程安全:
     * 还是stopRehash的问题
     * 因为先查找的老元素,所以保证线程安全(仔细分析可知),但是不能反过来查
     * 考虑这样的情况:
     *     异步线程执行到1,ret == null
     *     主线程突然执行StopRehash
     *     然后异步线程执行到3发现返回false,于是不执行4
     *     实际上元素就在rehashMap里面,但是由于切换状态导致查找失败
     *
     *     所以stopRehash必须在没有竞争的情况下执行
     * */
    public T get(Object key){
        T ret = map.get(key);
        if(ret == null && inRehashProgress()){//1
            greadLock();//2
            if(inRehashProgress()){//3
                // 扩容状态
                ret = rehashMap.get(key);//4
            }
            greadUnLock();
        }
        return ret;
    }

    /* 考虑线程安全: 如果先删除新的map可能在老的map正好向新map迁移的过程,导致一个都没有删除到
     * 考虑少删除的情况:
     *     如果执行到1,然后主线程立刻执行stopRehash就存在bug,这会导致原本存在rehashMap里面的元素被错误的忽略
     *
     * 对象释放: 在这个地方是需要进行释放;
     *
     *
     * */
    public void remove(K key) {
        map.remove(key);
        // 释放数据


        if(inRehashProgress()) {
            greadLock();
            if(inRehashProgress()){
                rehashMap.remove(key);
            }
            greadUnLock();
        }

        // 和put的情况类似,这里有可能是主线程,那么
        if(RedisServer.isCurrentThread() && needtrim(true)){
            startRehash();
        }
    }

    // 是否处于并发状态
    public boolean inConcurrent(){
        return inConcurrent;
    }

    // 如果还有其它hold这个,就不能toNormal
    // 注意这里不一定就是inConcurrent,因为主线程别的onComplete可能改变状态
    // 如果这个时候存在Rehash,那么就不去处理它
    public void toNormal(){
        assert RedisServer.isCurrentThread();
        decrementHold();
        if(inConcurrent && !holdByOther() && !inRehashProgress()) {
            inConcurrent = false;
            map = ((RedisConcurrentHashMap) map).getMap();
        }
    }

    public void toConcurrent(){
        assert RedisServer.isCurrentThread();
        incrementHold();
        if(!inConcurrent) {
            inConcurrent = true;
            map = new RedisConcurrentHashMap<>(map);
        }
    }

    // 是否正在进行rehash
    public boolean inRehashProgress(){
        return rehashMap != null;
    }

    // 这里同样要注意并发的情况
    // size加锁有可能导致死锁,如果外面已经加了写锁了,所以必须还要写一个单独的无锁版本出来(unsafeSize)
    public int size(){
        int ret;
        if(inRehashProgress()){
            greadLock();
            if(inRehashProgress()){
                ret = map.size() + rehashMap.size();
            }else{
                ret = map.size();
            }
            greadUnLock();
            return  ret;
        }else{
            // 非扩容状态,线程安全依赖于stopRehash的执行时刻
            return map.size();
        }
    }

    // 无锁版本,调用的时候外面一定要有锁
    public int unsafeSize(){
        if(inRehashProgress()){
            return map.size() + rehashMap.size();
        }else{
            return map.size();
        }
    }

    // 必须是线程安全的
    // todo 只在主线程运行
    public int length(){
        int ret;
        assert RedisServer.isCurrentThread();
        if(inRehashProgress()){
            greadLock();
            if(inRehashProgress()){
                // 扩容状态
                ret = map.length() + rehashMap.length();
            }else{
                // 不扩容状态
                ret = map.length();
            }
            greadUnLock();
        }else{
            // 不扩容状态,这里不会从扩容变成不扩容
            ret = map.length();
        }
        return ret;
    }

    public Node random(){
        assert RedisServer.isCurrentThread(); // 目前只能在主线程允许 todo 优化成可以在异步线程运行
        Node ret;
        if(inRehashProgress()){
            greadLock();
            if(inRehashProgress()){
                // 扩容状态
                if((ret = map.random()) == null){
                    ret = rehashMap.random();
                }
            }else{
                // 不扩容状态
                ret = map.random();
            }
            greadUnLock();
        }else{
            // 不扩容状态,由于只运行在主线程,所以不怕状态改编成扩容状态
            ret = map.random();
        }
        return ret;
    }

    // 如果上一次rehash的时间过短,不执行,少于1s都不会执行
    // 负载低于0.5
    // size为0的时候
    private boolean needtrim(boolean safe){
        int size = (safe ? size() : unsafeSize());
        return (map.length() > (2 * size + 1)) && (RedisTimerWheel.getSystemTimeMillSeconds() - lastRehash > 1000);
    }

    // 负载超过了1
    private boolean needGrowth(boolean safe){
        int size = (safe ? size() : unsafeSize());
        return (map.length() < size);
    }

    private void greadLock() {
        globalLock.readLock();
    }

    private void greadUnLock() {
        //globalLock.readLock().unlock();
        globalLock.readUnlock();
    }
    
    private void gwriteLock() {
        try{
             globalLock.writeLock();
        //boolean ret =  globalLock.writeLock().tryLock() || globalLock.writeLock().tryLock(3,TimeUnit.SECONDS);
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private void gwriteUnLock() {
        globalLock.writeUnlock();
        //globalLock.writeLock().unlock();
    }
}

/**  一个非常简单的读写自旋锁 **/
class MyReadWriteLock{
    AtomicInteger lock = new AtomicInteger(0); // 0 代表无锁, 1 代表
    int WRITE_STATE = 0x80000000;

    void writeLock(){
        int num = 0;
        while (!lock.compareAndSet(0,WRITE_STATE)){
            num++;
            if((num & 63) == 0){
                System.out.println("write locked");
            }
        }
    }

    void readLock(){
        int num = 0;
        int state;
        while (true){
            num++;
            if((num & 63) == 0){
                System.out.println("read locked");
            }
            if((state = lock.get()) >= 0 && lock.compareAndSet(state,state + 1)){
                break;
            }
        }
    }

    void readUnlock(){
        lock.decrementAndGet();
    }
    void writeUnlock(){
        lock.set(0);
    }
}