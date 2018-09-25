package RedisDataBase;

import Common.RedisUtil;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 这里同时还应该负责监控 一定时间内,获取和释放的比例
 *
 *
 *
 * **/

public abstract class AbstractObjectPool<T extends AbstractPooledObject> implements ObjectPool<T> {
     class ObjectContainer {
        private T elementData[];

        private int front,rear;

        private int mask;

        private int size;

        public ObjectContainer(int capacity){
            capacity = RedisUtil.sizeForTable(capacity);
            elementData = (T[]) new Object[capacity];
            front = rear=0;
            mask = capacity - 1;
        }

        public int size() {
            return size;
        }

        public boolean isEmpty() {
            return front==rear;
        }

        /**
         * data 入队,添加成功返回true,否则返回false,可扩容
         * @param data
         * @return
         */

        public boolean add(T data) {
            //判断是否满队
            if (this.front== ((this.rear + 1) & mask)){
                return false;
            }

            //添加data
            elementData[this.rear]=data;
            //更新rear指向下一个空元素的位置
            this.rear=(this.rear+1)%elementData.length;
            size++;
            return true;
        }


        /**
         * 出队,执行删除操作,返回队头元素,若队列为空,返回null
         * @return
         */

        public T poll() {
            T temp = this.elementData[this.front];
            this.front = ((this.front + 1) & mask);
            size--;
            return temp;
        }

        /**
         * 扩容的方法
         * @param capacity
         */
        public void ensureCapacity(int capacity) {
            //如果需要拓展的容量比现在数组的容量还小,则无需扩容
            if (capacity < size)
                return;

            T[] old = elementData;
            int newCapacity = RedisUtil.sizeForTable(capacity);
            elementData= (T[]) new Object[newCapacity];
            int j=0;
            //复制元素
            for (int i=this.front; i!=this.rear ; i=(i+1) & mask) {
                elementData[j++] = old[i];
            }
            // 恢复front,rear指向
            // 修改maks
            this.front=0;
            this.rear=j;
            this.mask = newCapacity -1;
        }
    }

    int lengthTable[];
    ObjectContainer[] objectPool;
    ConcurrentLinkedDeque<T> removedDeque; // 异步线程用来保存的放回的元素的
    Map<Integer,Integer> lenIndexMap; // todo 未来需要优化,变成数组更快

    int hugeThreshold = 4096;// 最大可以分配的对象
    double scaleDown = 0.5;// 衰减系数
    boolean usePool[];
    int[] estCum;// 估计值的累计
    int[] allocateStatistc;
    int[] deallocateStatistic;
    int[] accumulation;// 累计需要池化的次数

    abstract public void initLengthTable();
    abstract public T newInstance(int len);
    abstract public void initObjectPool();
    public AbstractObjectPool(){
        initLengthTable();
        initObjectPool();
        int lenNum = lengthTable.length;
        this.estCum = new int[lenNum];
        this.allocateStatistc =  new int[lenNum];
        this.deallocateStatistic = new int[lenNum];
        this.accumulation = new int[lenNum];
        this.usePool = new boolean[lenNum];
    }

    // 会尝试分配一个池化的对象,如果当前池不够用或者没有开启池化,那么就直接分配一个
    public T allocate(int len){
        if(len > hugeThreshold){
            return newInstance(len);// 直接分配一个对象
        }else{
            T allocated = getObject0(len);// 尝试从池化的过程获得一个对象
            return allocated == null ? newInstance(len) : allocated;
        }
    }

    // 查找具体的对象
    // todo 这里可以优化性能,indexOfLen重复计算了
    public T getObject0(int len){
        int startIndex = indexOfLen(len,false);// 先找最小
        int endIndex = indexOfLen(len,true);
        int firstSatisfiedIndex = 0;
        for(; startIndex >= 0 && startIndex <= endIndex ; ++startIndex){
            if(lengthTable[startIndex] >= len){
                // 计算第一个满足条件的index
                if(firstSatisfiedIndex != 0){
                    firstSatisfiedIndex = startIndex;
                }
                ObjectContainer container = objectPool[startIndex];
                if(container != null && !container.isEmpty()){
                    doStatisticBeforeAllocate(startIndex);
                    return container.poll();
                }
            }
        }

        // 没有找到,那么这种情况下说明需要获取
        doStatisticBeforeAllocate(firstSatisfiedIndex);
        return null;
    }

    // debug用,输出各个统计信息
    public void print(){
        int est;
        for(int index = 0; index < allocateStatistc.length; ++index){
            est = estimate(allocateStatistc[index],deallocateStatistic[index]);
            if(est > 0){
                System.out.println("size: " + lengthTable[index] + " allocate: " + allocateStatistc[index] + " 次" + "release: " + deallocateStatistic[index] + " 次, est: " + est );
            }
        }
    }


    // 需要创建
    // 作为定时任务运行,每5s检查一次,是否分配的速率和释放的速率大致相等(误差不超过20%)
    // 如果累计的满足条件的次数超过K(K = 3)次,那么就认为可以执行了
    public void usePoolWhenNeed(int k){
        for(int index = 0; index < allocateStatistc.length; ++index){
            int est;
            if((est = estimate(allocateStatistc[index],deallocateStatistic[index])) > 0){
                accumulation[index] += 1;
            }else if(est == 0){
                accumulation[index] -= 1;// 这里衰减的稍微快一点
                // 在没有启用池化的时候,不会将accumulation[index]变成负数,因为这个时候大概率长期不满足
                if(usePool[index] ==  false && accumulation[index] < 0){
                    accumulation[index] = 0;
                }
            }

            // 连续K次(目前k = 5)满足条件,那么就进行池化,同时会针对预测过去连续k次预测出的(分配-释放次数)进行加权求和,获得一个平均数
            ObjectContainer container = objectPool[index];
            if(accumulation[index] >= k){
                accumulation[index] = 0;
                if(objectPool[index] == null){
                    initializeOneList(index,est);
                }else{
                    // 存在3种情况(其中一种是不变)
                    // case1 est大于等于原来的size的1.5倍,池需要扩大
                    if(est >= container.size() * 1.5){
                        container.ensureCapacity(est);
                    }else if(est <= container.size() * 0.5 && container.size() >= 65536){
                        // case2 est小于原来的50%,且元素比较多,那么释放多余的引用,但是不会进行缩容
                        while (container.size() > est){
                            container.poll();
                        }
                    }
                }
            }else if(accumulation[index] <= -64){
                // 留一个64的Buffer,避免反复初始化 / 释放
                releaseOneList(index);
                accumulation[index] = 0;
            }
        }
    }

    // 大致相等
    public int estimate(long a,long b){
        if(a < 2048 || b < 2048){
            return 0;// 数据太小,不值得池化
        }else {
            long avg = (2 * a * b) / (a + b);// 调和平均数
            // 注意每5s检查一次得到的是过去5s平均数的2倍左右,这样保证一定冗余
            return (int)avg;
        }
    }


    // 分配次数 + 1
    public void doStatisticBeforeAllocate(int index){
        allocateStatistc[index] += 1;
    }

    // 移除次数 + 1
    public void doStatisticBeforeDeAllocate(int index){
        deallocateStatistic[index] += 1;
    }

    // 将定时运行
    // 每过T就进行衰减
    public void scaleDown(){
        for(int index = 0; index < objectPool.length; ++index){
            allocateStatistc[index] *= scaleDown;// 分配次数进行一次衰减
            deallocateStatistic[index] *= scaleDown; // 释放次数进行一次衰减
            //estCum[index] *= 0.3;// 让估计值进行一次衰减
        }
    }

    // 初始化一个类型的objectQueue
    // 同时还要执行初始化(预热)
    public void initializeOneList(int index,int capacity){
        if(usePool[index] == false){
            usePool[index] = true;
            ObjectContainer tmp = objectPool[index] = new ObjectContainer(capacity);
            int initialNum = Math.min(capacity >> 1,4096);
            for(int i = 0; i < initialNum; ++i){
                tmp.add(newInstance(lengthTable[index]));
            }
        }
    }

    // 释放一个类型的objectQueue
    public void releaseOneList(int index){
        if(usePool[index]){
            usePool[index] = true;
            objectPool[index] = null;
        }
    }

    // 返回一个object
    public boolean releaseObject( T obj){
        int index = indexOfObject(obj.size());// 这里使用false是为了确保放回pool之后,每一个list的元素的长度都大于list的len
        if(usePool[index]){
            return objectPool[index].add(obj);// 满了就自动忽略掉
        }
        doStatisticBeforeDeAllocate(index);
        return false;
    }

    // 异步线程来释放
     public void releaseInOtherThread(T obj){
         int index = indexOfObject(obj.size());// 这里使用false是为了确保放回pool之后,每一个list的元素的长度都大于list的len
         if(usePool[index]) {
             removedDeque.addLast(obj);
         }
     }

     //
    // 从异步线程的队列里面获取元素并放回主线程
    public void releaseFromRemovedDeque(){
        int i = 5000;
        while (i-- > 0 && releaseObject(removedDeque.pollFirst())){
        }
    }

    // 利用O(1)的时间定位到cap对应的size
    // @params useMax为true,则取较大的2^i,否则取较小的2^n,如果cap本身
    public int indexOfLen(int cap,boolean useMax){
        int n = RedisUtil.sizeForTable(cap);
        n = useMax ? n : ((cap & (cap - 1)) == 0 ? cap : n >> 1);
        if(n <= 8){
            return 0;
        }else if(n <= 16){
            return 1;
        }

        return lenIndexMap.get(n);
    }

    // 获取返回的object对应的index
    // 找到第一个小于等于自己大小的index
    public int indexOfObject(int size){
        if(size <= 8){
            return 0;
        }else if(size <= 16){
            return 1;
        }

        int index = lenIndexMap.getOrDefault(size,-1);
        return index >= 0 ? index : indexOfLen(size,true);
    }
}
