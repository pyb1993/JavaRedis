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
            elementData = (T[]) new AbstractPooledObject[capacity];
            front = rear = 0;
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
            this.rear=(this.rear+1) % elementData.length;
            size++;
            return true;
        }


        /**
         * 出队,执行删除操作,返回队头元素,若队列为空,返回null
         * @return
         */
        // 这里的front导致outofBound
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
            elementData = (T[]) new AbstractPooledObject[newCapacity];
            int j=0;
            //复制元素
            for (int i = this.front; i != this.rear ; i = (i + 1) & mask) {
                elementData[j++] = old[i];
            }
            // 恢复front,rear指向
            // 修改maks
            this.front = 0;
            this.rear = j;
            this.mask = newCapacity - 1;
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
    int[] stat;// todo removed,用来统计实际分配了多少次
    int[] stat2;// todo removed,用来统计new了多少次

    abstract public void initLengthTable();
    abstract public T newInstance(int len);
    abstract public void initObjectPool();
    abstract public ObjectContainer getSubObjContainer(int size);// 用来获取真正类型的Container
    public AbstractObjectPool(){
        initLengthTable();
        initObjectPool();
        int lenNum = lengthTable.length;
        this.estCum = new int[lenNum];
        this.allocateStatistc =  new int[lenNum];
        this.deallocateStatistic = new int[lenNum];
        this.accumulation = new int[lenNum];
        this.usePool = new boolean[lenNum];
        this.removedDeque = new ConcurrentLinkedDeque<>();
        stat = new int[lenNum];
        stat2 = new int[lenNum];
    }


    // 会尝试分配一个池化的对象,如果当前池不够用或者没有开启池化,那么就直接分配一个(至少要分配lengthTable[0]大小的长度)
    public T allocate(int len){
        len = len < lengthTable[0] ? lengthTable[0] : len;
        if(len > hugeThreshold){
            return newInstance(len);// 直接分配一个对象
        }else{
            int index = indexOfLen(len);
            doStatisticBeforeAllocate(index);
            T allocated = getObject0(len);// 尝试从池化的过程获得一个对象
            if(allocated != null){
                stat[indexOfLen(len)]++;
            }else{
                stat2[indexOfLen(len)]++;
            }
            allocated =  allocated == null ? newInstance(lengthTable[index]) : allocated;
            allocated.cancelReleasedMark();// 取消released标志
            return allocated;
        }
    }

    // 查找具体的对象
    // todo 这里可以优化性能,indexOfLen重复计算了
    public T getObject0(int len){
        int startIndex = indexOfLen(len);
        for(;; ++startIndex){
            ObjectContainer container = objectPool[startIndex];
            if(container != null && !container.isEmpty()){
                return container.poll();
            }
            if(lengthTable[startIndex] > 2 * len){
                break;
            }
        }

        // 没有找到,那么这种情况下说明需要获取
        return null;
    }

    // debug用,输出各个统计信息
    // todo 发现bug 很多地方没有通过allocate来的
    public void print(){
        int est;
        for(int index = 0; index < allocateStatistc.length; ++index){
            est = estimate(allocateStatistc[index],deallocateStatistic[index]);
            if(allocateStatistc[index] > 100){
                System.out.println("size: " + lengthTable[index] + " allocate: " + allocateStatistc[index] +
                        " 次" + " release: " + deallocateStatistic[index] + " 次, est: " + est +
                        "实际复用: " + stat[index] + "新分配: " + stat2[index] + "现有队列大小:" + "" + (usePool[index] ? (objectPool[index] == null ? 0 : objectPool[index].elementData.length) : 0));
            }
            stat2[index] = 0;
            stat[index] = 0;
        }
    }


    // 需要创建
    // 作为定时任务运行,每Ts检查一次,是否分配的速率和释放的速率大致相等(误差不get lock failed超过20%)
    // 如果累计的满足条件的次数超过K(K = 3)次,那么就认为可以执行了
    public void usePoolWhenNeed(int k){
        for(int index = 0; index < allocateStatistc.length; ++index){
            int est;
            if((est = estimate(allocateStatistc[index],deallocateStatistic[index])) > 0){
                // 如果长期空着,就会是负数,折半衰减,至多10次就可以重新分配对象池
                if(accumulation[index] < 0){
                    accumulation[index] /= 2;
                }
                accumulation[index] += 1;
            }else if(est == 0){
                accumulation[index] -= 1;
                // 在没有启用池化的时候,不会将accumulation[index]变成负数,因为这个时候大概率长期不满足
                if(!usePool[index] && accumulation[index] < 0){
                    accumulation[index] = 0;
                }
            }

            // 连续K次满足条件,那么就进行池化,同时会针对预测过去连续k次预测出的(分配-释放次数)进行加权求和,获得一个平均数
            ObjectContainer container = objectPool[index];
            if(accumulation[index] >= k){
                accumulation[index] = 0;
                if(objectPool[index] == null){
                    initializeOneList(index,est);
                }else{
                    // case1 est大于等于原来的size的1.5倍,池需要扩大
                    if(est >= container.size() * 1.5){
                        System.out.println("resize the objectContainer");
                        container.ensureCapacity(est);
                    }

                  /*  else if(est <= container.size() * 0.25 && container.size() >= 65536){
                        // case2 est小于原来的25%,且元素比较多,那么释放多余的引用,但是不会进行缩容
                        while (container.size() > est){
                            container.poll();
                        }
                    }*/
                }
            }else if(accumulation[index] <= -1024){
                // 留一个Buffer,避免反复初始化 / 释放
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
        }
    }

    // 初始化一个类型的objectQueue
    // 同时还要执行初始化(预热)
    public void initializeOneList(int index,int capacity){
        if(usePool[index] == false){
            usePool[index] = true;
            // 要求元素的实际类型必须能够存储到 objectPool(由子类型通过initObjectPool来确定实际类型: StringContainer[])里面
            // 所以不能直接生成ObjectContainer(父类型)
            ObjectContainer tmp = objectPool[index] = getSubObjContainer(capacity);
            int initialNum = Math.min(capacity >> 1,4096);
            for(int i = 0; i < initialNum; ++i){
                stat2[index] += 1;
                tmp.add(newInstance(lengthTable[index]));
            }
        }
    }

    // 释放一个类型的objectQueue
    public void releaseOneList(int index){
        if(usePool[index]){
            System.out.println("Release One List");
            usePool[index] = false;
            objectPool[index] = null;
        }
    }

    // 返回一个object
    // 这里要求 size >= lengthTable[index],否则allocate的时候会造成分配的内存不足
    public boolean releaseObject( T obj){
        int size = obj.cap();
        int index = indexOfLen(size);// 这里使用false是为了确保放回pool之后,每一个list的元素的长度都大于list的len
        while (size < lengthTable[index] && index > 0){
            index--;
        }

        obj.setReleasedMark();//设置释放标志
        doStatisticBeforeDeAllocate(index);
        if(usePool[index]){
            return objectPool[index].add(obj);// 满了就自动忽略掉
        }
        return false;
    }

    // 异步线程来释放
     public void releaseInOtherThread(T obj){
         obj.setReleasedMark();//设置释放标志
         int index = indexOfLen(obj.size());// 这里使用false是为了确保放回pool之后,每一个list的元素的长度都大于list的len
         if(usePool[index]) {
             removedDeque.addLast(obj);
         }
     }

     //
    // 从异步线程的队列里面获取元素并放回主线程
    public void releaseFromRemovedDeque(){
        int i = 10000;
        T first;
        while (i-- > 0 && (first = removedDeque.pollFirst()) != null && releaseObject(first)){
        }
    }

    // 利用O(1)的时间定位到cap对应的index
    // 找到第一个满足条件的index,所以先找到一半的n,然后向后找
    public int indexOfLen(int cap){
        int index;
        int n = RedisUtil.sizeForTable(cap);
        n = (cap & (cap - 1)) == 0 ? cap : n >> 1;
        if(n <= lengthTable[0]){
            index = 0;
        }else if(n <= lengthTable[1]){
            index = 1;
        }else{
            index = lenIndexMap.get(n);
        }

        while (lengthTable[index] < cap){
            index++;
        }

        return index;
    }

}
