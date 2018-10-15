package RedisDataBase;

import Common.RedisUtil;
import sun.misc.Unsafe;

import javax.swing.tree.TreeNode;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


class Node<K,V>{
    final int hash;
    final K key;
    V value;
    Node<K,V> next;

    Node(int hash, K key, V value, Node<K,V> next) {
        this.hash = hash;
        this.key = key;
        this.value = value;
        this.next = next;
    }

    public final K getKey()        { return key; }
    public final V getValue()      { return value; }
    public final int hashCode() {
        return Objects.hashCode(key) ^ Objects.hashCode(value);
    }
    public final boolean equals(Object o) {
        if (o == this)
            return true;
        if (o instanceof Map.Entry) {
            Map.Entry<?,?> e = (Map.Entry<?,?>)o;
            if (Objects.equals(key, e.getKey()) && Objects.equals(value, e.getValue())) {
                return true;
            }
        }
        return false;
    }
}

// 用来处理rehash情况
// todo 这里必须要全部重写一遍了,因为自动扩容没有办法控制,即便在rehash的时候也是线程不安全的
class RedisHashMap<K,V>  {
    Random rand = new Random();
    transient Node<K,V>[] table;
    int size = 0;

    public RedisHashMap(){
        this(1);
    }

    public RedisHashMap(int initialSize){
        if(initialSize >= 1048576){
        }

        table = new Node[tableSizeFor(initialSize)];
    }

    /* 为了使用多态
     *  这里需要使用对象池将原来的对象进行归还
     */
    public void remove(Object key,boolean releaseNow) {
        Node<K,V> e;
        e = removeNode(hash(key), key, null, false);
        if(e != null){
            RedisUtil.doRelease(e.key,!releaseNow);
            doReleaseRedisObject(e.value,false);
        }
    }

    // 这里会进行release
    // true代表移除成功, false代表没有这个数据
    public Boolean remove2(Object key,boolean releaseNow){
        Node<K,V> e;
        e = removeNode(hash(key), key, null, false);
        if(e != null){
            RedisUtil.doRelease(e.key,!releaseNow);
            doReleaseRedisObject(e.value,false);
        }
        return e != null;
    }

    void doReleaseRedisObject(Object ro,boolean lazy){
        if(ro instanceof RedisObject){
            RedisObject o = (RedisObject) ro;
            RedisUtil.doRelease(o.getData(),lazy);//另外老的value也可以直接释放,因为不会再用到了
        }
    }



    final Node<K,V> removeNode(int hash, Object key, Object value, boolean matchValue) {
        Node<K,V>[] tab = table;
        Node<K,V> p;
        int n = table.length, index;
        if ((p = tab[index = (n - 1) & hash]) == null){
            return null;
        }

        Node<K,V> node = null, e; K k; V v;
        if (p.hash == hash && ((k = p.key) == key || (key != null && key.equals(k)))){
            node = p;
        } else if ((e = p.next) != null) {
            do {
                if (e.hash == hash && ((k = e.key) == key || (key != null && key.equals(k)))) {
                    node = e;
                    break;
                }
                p = e;
            } while ((e = e.next) != null);
        }

        if (node != null && (!matchValue || (v = node.value) == value || (value != null && value.equals(v)))) {
            if (node == p) {
                tab[index] = node.next;
            }
            else {
                p.next = node.next;
            }
            --size;
            return node;
        }
        return null;
    }


    public V get(Object key) {
        Node<K,V> e;
        return (e = getNode(hash(key), key)) == null ? null : e.value;
    }

    // protected方法
    final Node<K,V> getNode(int hash, Object key) {

        Node<K,V>[] tab; Node<K,V> first, e; int n; K k;
        if ((tab = table) != null && (n = tab.length) > 0 &&
                (first = tab[(n - 1) & hash]) != null) {
            if (first.hash == hash && // always check first node
                    ((k = first.key) == key || (key != null && key.equals(k))))
                return first;
            if ((e = first.next) != null) {

                do {
                    if (e.hash == hash &&
                            ((k = e.key) == key || (key != null && key.equals(k))))
                        return e;
                } while ((e = e.next) != null);
            }
        }
        return null;
    }

    // return true代表有老数据， return false代表没有老的数据
    public boolean put(K key, V value,boolean releaseKeyNow) {
        V ret = putVal(hash(key), key, value, false);
        if(ret != null){
            RedisUtil.doRelease(key,!releaseKeyNow);// 可能延迟释放这次的key,因为后面逻辑处理可能会用到这个key
            doReleaseRedisObject(ret,false);
            return true;
        }
        return false;
    }

    final V putVal(int hash, K key, V value, boolean onlyIfAbsent) {
        Node<K,V>[] tab; Node<K,V> p;
        int n = table.length;
        int i;
        tab = table;

        if ((p = tab[i = (n - 1) & hash]) == null) {
            tab[i] = newNode(hash, key, value, null);

        }
        else {
            Node<K,V> e; K k;
            if (p.hash == hash && ((k = p.key) == key || (key != null && key.equals(k)))){
                e = p;
            } else {
                 while (true){
                    if ((e = p.next) == null) {
                        p.next = newNode(hash, key, value, null);
                        break;
                    }
                    if (e.hash == hash && ((k = e.key) == key || (key != null && key.equals(k)))){
                        break;
                    }
                    p = e;
                }
            }
            if (e != null) { // existing mapping for key
                V oldValue = e.value;
                if (!onlyIfAbsent || oldValue == null){
                    e.value = value;
                }

                // afterNodeAccess(e);
                return oldValue;
            }
        }

        ++size;
        return null;
    }


    public void rehash(){
        if(size > table.length){
            regrow();
        }else if(size < table.length / 2){
            retrim();
        }
    }

    // 自动发起的rehash
    // 只能针对普通map
    final void regrow(){
        Node<K,V>[] oldTab = table;
        int oldCap = oldTab.length;
        int newCap = oldCap << 1;

        Node<K,V>[] newTab = (Node<K,V>[])new Node[newCap];
        table = newTab;
        for (int j = 0; j < oldCap; ++j) {
            Node<K,V> e;
            if ((e = oldTab[j]) == null){
                continue;
            }

            oldTab[j] = null;
            if (e.next == null){
                newTab[e.hash & (newCap - 1)] = e;
                continue;
            }

            // 下面是处理多元素链表的过程,一次遍历将链表分成两部分
            Node<K,V> loHead = null, loTail = null;
            Node<K,V> hiHead = null, hiTail = null;
            Node<K,V> next;

            do {
                next = e.next;
                if ((e.hash & oldCap) == 0) {
                    if (loTail == null)
                        loHead = e;
                    else
                        loTail.next = e;
                    loTail = e;
                }
                else {
                    if (hiTail == null)
                        hiHead = e;
                    else
                        hiTail.next = e;
                    hiTail = e;
                }
            } while ((e = next) != null);

            if (loTail != null) {
                loTail.next = null;
                newTab[j] = loHead;
            }
            if (hiTail != null) {
                hiTail.next = null;
                newTab[j + oldCap] = hiHead;
            }
        }
    }

    // 自动发起的缩容
    // 只能针对普通map
    final void retrim(){
        Node<K,V>[] oldTab = table;
        int oldCap = oldTab.length;
        int newCap = oldCap >> 1;

        // todo 严重错误
        @SuppressWarnings({"rawtypes","unchecked"})
        Node<K,V>[] newTab = (Node<K,V>[])new Node[newCap];
        table = newTab;
        for (int j = 0; j < oldCap / 2; ++j) {
            Node<K,V> le = oldTab[j];
            Node<K,V> he = oldTab[j + newCap];
            oldTab[j] = null;
            oldTab[j + newCap] = null;

            // 下面是处理多元素链表的过程,一次遍历将j 和 j + newCap的位置都尝试一遍
            newTab[j] = mergeSlots(le,he);
        }
    }

    final Node mergeSlots(Node first, Node second){
        if(first == null && second == null) return null;
        if(first == null || second == null) {return first == null ? second : first;}

        // 从头部插入second的最后一个节点
        Node sLast = second;
        while (sLast.next != null){
            sLast = sLast.next;
        }

        sLast.next = first;
        return second;
    }

    public int size(){return  size;}
    public int length() {
        return table.length;
    }

    // 首先随机获取一个Entry,这个随机不是真随机,是假随机
    // 首先获取一个Node
    // 接下来按概率往前走1-4步

    public Node random(){
        int len = length();
        int times = 0;
        Node e;
        do{
            int index = rand.nextInt(len);
            e = table[index];
        }while (e == null && times++ < 16);

        if(e != null && e.next != null){
            int step = rand.nextInt(4);
            while (e.next != null && step-- > 0){
                e = e.next;
            }
        }
        return e;
    }

    protected int hash(Object key){
        int h;
        h = (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
        return h;
    }

    static final int tableSizeFor(int cap) {
        int n = cap - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return (n < 0) ? 1 : n + 1;
    }

    Node<K,V> newNode(int hash, K key, V value, Node<K,V> next) {
        return new Node<>(hash, key, value, next);
    }

}


