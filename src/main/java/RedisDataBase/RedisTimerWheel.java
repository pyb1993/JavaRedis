package RedisDataBase;

import Common.Logger;
import RedisFuture.ExpireFuture;
import RedisServer.RedisServer;

import java.awt.image.SampleModel;
import java.lang.ref.WeakReference;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

// 实现的一个定时轮
/**
 * 缺点 1 不能以O(1)的时间找到过期的数据,也就是检查的时候比较麻烦
 *     2 不方便修改过期的时间,因为这会导致过期
 *     3 如果对过期key做LRU淘汰的时候不方便,用定时轮来做淘汰,可以相对精确一些
 *
 *     考虑极端情况下很可能有非常多的过期key,这个时候Dict的开销就会很大
 *
 * 弥补 为所有的过期key存储一个dict
 * 这是一个相对失败的设计,因为定时清除过期key的目的就是减少内存的使用
 * 但是这里确偏偏又使用了相对较多的内存,好处是让内存清理的更快一些
 * 同时如果TPS够大,可能导致的一个slot里面有过多的entry从而拖慢速度
 *
 *
 *  实现要点:
 *      1 indexOffset todo 似乎还是有问题,因为存在delay,它完全可以插入到某一个dict里面
 *      2 RedisMap -> RedisConcurrentHashMap的实现,必须保证线程安全
 *          1 目的,对于 isExpired,add,remove操作进行同步
 *          2 要考虑持有的线程有哪些:
 *              1 对于ExpiredDict 持有的线程目前有: 主线程, 异步清理线程
 *              2 对于RedisMap,持有的线程目前有: 主线程, 异步清理线程, 扩容线程
 *          3 思路: 在转变的时候,首先肯定是在主线程执行的,所以主线程不用考虑同步
 *                 如果当前RedisHashMap是普通状态,就必须切换成并发状态,否则忽略
 *                 1 如果是普通状态,那么就意味着没有 扩容线程 或者 清理线程,所以可以直接将Map修改
 *                 2 如果已经是并发状态,就不需要切换了
 *                 3 当线程结束的时候,必须在主线程里面切换到原来的状态
 *                   所以这里面的思路就是增加一个引用计数,当引用计数变成0的时候才能执行切换回来的操作
 *                 4 关键是状态切换时候线程安全,首先必须增加一个引用计数,所有的状态切换都只能在主线程执行
 *                      当引用计数变成0的时候才能执行切换回来的操作
 *                      当引用计数不为0的时候就不需要进行切换,只要增加一个引用即可
 *
 * **/
public class RedisTimerWheel {
    static int slotNum = 1;
    static PriorityList[] expires;
    static long lastProcessed;
    static long systemTime;


    static {
        expires = new PriorityList[slotNum];
        for(int i = 0; i < slotNum; ++i){
            expires[i] = new PriorityList();
        }
    }

    // 首先获取系统当前的时间 now
    // 然后计算这个任务应该放到哪里
    void enqueue(String key,int delay){
        long now = getSystemSeconds();
        if(delay < slotNum * 4){
            /* 如果是那种延迟很大的过期key,就不放在定时轮里面,而是采用原来redis的随机抽样做法
            *  因为如果过期时间很长,就会导致定时轮里面存储太多数据,从而导致插入变慢,而且占用太多内存
            *
            * */
            int index = (int)((now + delay ) & (slotNum - 1));// 计算应该放到的index
            PriorityList slot = expires[index];
            slot.add(new ExpireObject(key,now + delay));
            System.out.println("add index");
        }
    }

    /** 处理过期数据
     *  运行的频率设定为200ms一次,每次运行不超过25ms
     *  运行时需要执行以下几个步骤:
     *      1 更新上一次处理的时间
     *      2 开始循环删除,获取当前slot里面的元素,时间不超过25ms
     *      3 todo 如果发现长期删除不完,连续2次以及以上出现25ms满的情况
     *             那么就应该提交一个异步的任务到一个线程,这个线程负责开始删除所有的expires
     *             异步任务的思路就是:
     *               将该slot直接取出来,提交到异步线程上面(由于单线程删除速度应该很快),获取future检查,所以启动一个定时任务1s后进行检查
     *               完成后将该slot直接如果没有为空,那么就重启插入
     *
     * **/
    void processExpires(){
        lastProcessed = getSystemTimeMillSeconds();
        long now = lastProcessed / 1000;
        int curIndex = (int)(now  & (slotNum - 1));
        int num = 0;
        PriorityList slot = expires[curIndex];
        if(!slot.isSubmitted()){
            ExpireObject obj;
            while((obj = slot.poll()) != null){
                if(obj.getExpireTime() > now){
                    slot.add(obj);// 重新加入
                    break; // 当前时间slot里面没有需要删除的元素
                }

                // 下面开始进行删除操作,经测试大约1ms可以处理800-1000左右的过期元素
                num++;
                RedisDb.del(obj.getKey());
                if((num & 255) == 0){
                    updateSystemTime();
                    System.out.println("index:" + curIndex + " num:" + num + " used time " + (getSystemTimeMillSeconds() - lastProcessed));
                    if((getSystemTimeMillSeconds() - lastProcessed) > 5 && slot.size() > 255 && !slot.isSubmitted()){
                        // 超过5ms,且剩余的元素也很多,并且没有提交过任务那么直接提交一个异步任务
                        System.out.println("submit task");
                        submitTaskToHelper(curIndex,now);// 这会导致slot的状态发生变化
                        break;
                    }
                }
            }
        }

        /* 下面开始尝试原始Redis的随机算法
         * 目的是避免过期时间太久的数据占用太多内存
         * 如果应用存在很多过期时间很长的数据,那么就会导致在上面进行删除之后同样存在大量的过期数据(因为没有被定时轮记录)
         * 所以用随机抽样来进行处理
         * 1 但是如果由于定时轮里面的数据太多导致删除不完,也有可能进行导致采样出现误差,所以只有没有存在定时任务的情况下才会尝试采样
         * 2 如果有其他线程正在操作 ExpiresDict(rehash,或者执行异步任务),那么也不进行抽样(todo 把随机操作也修改成线程安全的操作)
         *      这里如果进行rehash的话,最麻烦的地方就是要同时同步操作到两个地方,这个非常麻烦
         *
         * 3 如果expireDict里面不为空的slot占比例太少(少于10%,运行10次才得到一次),那么也不执行随机抽样
         *   等待数据的缩容程序执行(缩容程序如何实现)
         * 随机算法的思路:
         *      这个并不是很好操作,因为肯定不能直接转换成Array来操作,这会导致过多的复制
         *      所以利用Unsafe + 反射
         *
         * */
        RedisHashMap ExpiresDict = RedisDb.ExpiresDict;
        RedisHashMap RedisMap = RedisDb.RedisMap;
        if(!slot.isSubmitted() && !ExpiresDict.holdByAnother()){
            int round = 16;

            int size = ExpiresDict.size();
            int slots = ExpiresDict.unsafeTable.length;
            if(size < 100 || ((slots / size) > 10)){
                return; // 数量太少,不更新
            }

            updateSystemTime();
            lastProcessed = getSystemTimeMillSeconds();
            while (true){
                int expired = 0;
                for(int i = 0; i < round; ++i) {

                    Map.Entry e = ExpiresDict.random();
                    if (e != null){
                        Long expireTime = (Long) e.getValue();
                        if(expireTime > getSystemSeconds()){
                            RedisDb.del((String) e.getKey());
                            expired++;
                        }
                    }
                }

                // 失效的key小于 %25,那么可以直接结束了
                if(expired < 4){
                    System.out.println("太少:" + expired);
                    break;
                }

                // 检查时间,目前不能超过5ms
                updateSystemTime();
                if((getSystemTimeMillSeconds() - lastProcessed) > 5){
                    System.out.println("随机抽取时间: " + (getSystemTimeMillSeconds() - lastProcessed) + "expired :" + expired);
                    break;
                }
            }
        }

       // System.out.println("used time" + (getSystemTimeMillSeconds() - lastProcessed));
    }

    // 超过10ms,且剩余的元素也很多,那么直接提交一个异步任务
    // todo 关键是需要把指定slot替换成Concurrent的状态
    // 和expires竞争的实际上只有enqueue
    // 稍微延迟一下再执行,避免这个时候和enqueu产生大量竞争
    private void submitTaskToHelper(int curIndex,long now){
        RedisDb.converMapToConcurrent();
        RedisDb.convertExpiredToConcurrent();
        convertTimerWheelToConcurrent(curIndex);
        PriorityList slot = expires[curIndex];

        Future expireFuture = RedisServer.ExpireHelper.schedule(()->{
            ExpireObject o;
            // 注意需要将下面对地方锁住,否则会导致大量对
            while((o = slot.poll()) != null){
                if(o.getExpireTime() > now){
                    slot.add(o);// 重新加入
                    break; // 当前时间slot里面没有需要删除的元素
                }
                // 目的是确保线程安全
                RedisDb.removeIfExipired(o.getKey());
            }
        },
          100 + new Random().nextInt(500),
         TimeUnit.MILLISECONDS
        );
        RedisServer.addFuture(new ExpireFuture(curIndex,expires,expireFuture));// 将需要的结果加入
    }

    /* 将slot转换成并发的模式*/
    static void convertTimerWheelToConcurrent(int index){
        PriorityList old = expires[index];
        expires[index] = new ConcurrentPriorityList(old);
    }

    /*转换为正常
    * 这里只可能有一个位置引用
    * */
    static public void convertTimerWheelToNormal(int index){
        expires[index] = ((ConcurrentPriorityList)expires[index]).getList();
    }

    // 获取系统的秒
    static long getSystemSeconds(){
        long now = System.currentTimeMillis();
        return now / 1000;
    }

    // 更新时间缓存
    static public void updateSystemTime(){
        systemTime = System.currentTimeMillis();
    }

    static long getSystemTimeMillSeconds(){
        return  systemTime;
    }

}




