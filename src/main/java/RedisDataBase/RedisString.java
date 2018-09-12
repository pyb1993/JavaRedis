package RedisDataBase;


import RedisServer.RedisServer;

/* 实现的一个RedisString的数据结构
*  实现这玩意的目的:
*   String是不可变的,但是这导致无法将String池化
*   Redis的其余一些改造String的东西也暂时没有办法实现
*   编码: 无视编码,统统按byte操作,把这个工作交给客户端
*   内存大小:
*       首先我们的String是可以自动增加内存的
*       增加的方式也很简单,就是 * 1.5倍的大小
*
*
* */
public class RedisString extends AbstractPooledObject {
    int len;
    int hash;
    byte[] bytes;
    static RedisStringPool pool = new RedisStringPool();

    public RedisString(int len){
        bytes = new byte[len];
        len = bytes.length;
    }

    public RedisString(String s){
        bytes = s.getBytes();
        len = bytes.length;
    }

    @Override
    public int hashCode(){
        int h = hash;
        if(h != 0){
            return h;
        }
        for (byte v : bytes) {
            h = 31 * h + (v & 0xff);
        }
        return hash = h;
    }

    // 是否相等
    public boolean equal(RedisString other) {
        byte[] otherBytes = other.bytes;
        if (bytes.length == bytes.length) {
            for (int i = 0; i < len; i++) {
                if (bytes[i] != otherBytes[i]) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    public int len(){
        return len;
    }

    public int size(){
        return bytes.length;
    }

    // 释放的时候要进行释放
    public void release(){
        len = 0;
        if(RedisServer.isCurrentThread()){
            pool.releaseObject(this);
        }else{
            // 先放到一个并发数据结构里面,然后主线程再读
            pool.releaseInOtherThread(this);
        }
    }
}
