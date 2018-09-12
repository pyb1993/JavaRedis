package RedisDataBase;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

/**
 * 支持并发的HashMap
 * 即便用了分段锁,也有一些地方是不安全的
 * 其中最重要的一点是size是不安全的,因为size++不是一个线程安全的操作
 * 所以需要在转换为正常的时候,将真正的size也重新写入
 * 另外如果另外一个线程导致扩容/缩容,那么就会导致线程不安全,所以必须把扩容和缩容禁止,由RedishHashMap来主动执行
 * 注意对于RedisConcurrentHashMap来说,一个可能的问题是:
 *      rehashMap的切换不是线程安全的 考虑到过期线程正在进行操作
 *      异步线程, 我们首先进行get的判断(这个时候还没有进行rehash操作),发现没有这个数据
 *      执行了inRehashProgress(),判断为执行了
 *      主线程执行stopRehash操作,导致rehash变成null
 *
 *
 *
 * 扩容有3种: 一种是普通的Java自带的同步扩容
 *           一种是Redis的渐进扩容
 *           一种是这里要实现的 同步 + (渐进 + 异步) 扩容的组合方式
 * 注意一点,RedisHashMap实际上可以使用在RedisObject里面
 *      1 todo 把扩容做在RedisHashMap的里面,办法就是put之前先检查一次,一但接近要自动扩容的上限,我们立刻主动扩容
 *        只要扩容,立刻就先转换成并发模式
 *        所以只需要在RedisConcurrentHashMap里面写相关逻辑就可以了
 *      2 什么时候切换回来,需要一个future,用来在主线程执行切换的回调
 *      3 遍历时候的线程安全问题:
 *          我们进行遍历的时候,一个特殊的策略就是确保每一个slot当前状态下是线程安全的即可,所以每一个slot都要锁住
 *
 *
 * **/
public class RedisConcurrentHashMap<K,T> extends RedisHashMap<K,T>{
    /*下面是成员变量*/
    int lockNum;
    EasyLock[] lockArr;// 构造的并发锁
    AtomicInteger tmpSize;// 用来记录size的
    RedisHashMap<K,T> map;// 真正装数据的地方

    /* 选取一个合适的锁的数量,min(1024,map数量) */
    public RedisConcurrentHashMap(RedisHashMap map){
        this.map = map;
        tmpSize = new AtomicInteger(map.size());
        lockNum = Math.min(1024,tableSizeFor(map.length()));
        lockArr =  new EasyLock[lockNum];
        for(int i = 0; i < lockNum; i++){
            lockArr[i] = new EasyLock();
        }
    }

    // 注意,需要将map的size重新修改
    public RedisHashMap<K,T> getMap(){
        try {
            map.size = tmpSize.get();
        }catch (Exception e){
            e.printStackTrace();
        }
        return map;
    }

    /*
     * 先删除 rehashMap,可能的线程安全问题:
     * A线程删除rehashMap发现没有,然后B线程将map -> rehashMap
     * 此时A线程删除map.发现也没有,这样就删除失败
     *
     * 所以先删除map,删除失败,
     * 异步线程将 map -> rehashMap
     * 然后删除rehashMap删除成功
     *
     * */

    /*
     * 在异步线程执行进行rehash操作,其实这里不在乎性能,所以
     * 这个index实际上就是通过HashCode获取的,任何一个key一定会定位到对应的index
     * 因为 hash & (length -1) == index,所以可以保证这个slot上的key一定会和lockArr[index]冲突
     * */
    public void reHashOneSlot(int index,RedisHashMap rehashMap){
        EasyLock lock = lockArr[index & (lockNum - 1)];
        lock.lock(); // 需要lock住固定的index
        Node node = map.table[index];
        int nodeNum = 0;
        while (node != null){
            // 进行rehash
            ++nodeNum;
            rehashMap.put(node.getKey(),node.getValue());// 自动加锁
            node = node.next;
        }
        map.table[index] = null;//所有的都设置为null,这样就成功将map本身给解决了,map本身的size也应该修改
        tmpSize.addAndGet(-nodeNum);
        lock.unlock();
    }


    // 应该不存在其它地方可以同时运行了
    public T remove(Object key){
        T ret;
        lock(key);
        ret = map.remove(key);
        if(ret != null){
            tmpSize.decrementAndGet();
        }
        unlock(key);
        return  ret;
    }


    /**
     * 这里是线程安全的: 假设 因为操作只会从map -> rehashMap里面执行
     * **/
    public T get(Object key){
        lock(key);
        T ret = map.get(key);
        unlock(key);
        return ret;
    }

    public T put(K key,T val){
        lock(key);
        T old = map.put(key,val);
        if(old == null){
            tmpSize.incrementAndGet();
        }
        unlock(key);
        return old;
    }

    // 如果在主线程执行它,然后异步线程突然执行 put／get,那么将会导致线程不安全
    // 所以这里确保禁止执行
    public void rehash(){
       assert false;
    }

    /*
    * 考虑线程安全问题
    * 第一,由于random只在主线程执行,所以不需要担心别的线程突然执行rehash操作导致tale变化
    * 第二,异步线程会访问table执行o,所以必须将对应的锁锁住
    *
    * */
    public Node random()
    {
        int len = length();
        int times = 0;
        int index;
        EasyLock lock;
        Node e;
        while (true){
            index = rand.nextInt(len);
            lock = lockArr[index & (lockNum - 1)];
            lock.lock(); // 在这里锁住

            e = map.table[index];
            if(e != null){
                // 尝试走几步
                int step = rand.nextInt(4);
                while (e.next != null && step-- > 0){
                    e = e.next;
                }
                break;
            }

            if(times++ < 15){
                // 解锁对应的锁,然后下一次
                lock.unlock();
            }else{
                break;// 尝试次数太多,直接结束
            }
        }

        // 可以确定上面一定会锁住
        lock.unlock();
        return e;
    }

    // 这里可能正在扩容,需要考虑
    public int size(){
        return tmpSize.get();
    }

    public int length(){
        return map.length();
    }

    public void lock(Object key){
        getLock(key).lock();
    }

    public void unlock(Object key){
        getLock(key).unlock();
    }

    private EasyLock getLock(Object key){
        int slotIndex = getHash(key) & (length() - 1);
        return lockArr[slotIndex & (lockNum - 1)];
    }

    private int getHash(Object key){
        int h;
        h = (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
        return h;
    }


}

/** 一个非常简单的自旋锁 **/
class EasyLock {
    AtomicBoolean lc;

    public EasyLock(){
        lc = new AtomicBoolean(false);
    }

    public void lock(){

        int num = 1;
        while (true) {
            num++;
            if(lc.get() == false && lc.compareAndSet(false,true)){
                break;
            }
            System.out.println("get lock failed");
            if((num & 7) == 0){
                Thread.yield();
            }
        }
        return;
    }

    public void unlock(){
        assert lc.get();
        while (!lc.compareAndSet(true,false)){
            System.out.println("unlock failed");
        }
        return;
    }
}

