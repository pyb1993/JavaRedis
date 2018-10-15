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
* */

// todo 暴露bytes是为了方便配合ByteBuf来使用的,以后可以改成传入一个bytebuf
public class RedisString extends AbstractPooledObject {
    int hash;
    public int size;// 字符长度
    public byte[] bytes;
    public final static RedisStringPool pool = new RedisStringPool();

    public static RedisString allocate(int len){
        return pool.allocate(len);
    }

    public static RedisString allocate(String s){return pool.getString(s);}
    public static RedisString allocate(RedisString s,int pos,int len){
        return pool.getString(s,pos,len);
    }

    public RedisString(int len){
        bytes = new byte[len];
        this.size = 0;
    }

    public RedisString(String s){
        bytes = s.getBytes();
        size = bytes.length;
    }

    // check params valid
    public byte getByte(int i){
        return bytes[i];
    }

    public void setSize(int size){
        this.size = size;
    }

    @Override
    public int hashCode(){
        int h = hash;
        if(h != 0){
            return h;
        }

        for (int i = 0; i < size; ++i) {
            h = 31 * h + (bytes[i] & 0xff);
        }
        return hash = h;
    }

    // 是否相等
    public boolean equals(Object other) {
        if(other instanceof RedisString) {
            byte[] otherBytes = ((RedisString)other).bytes;
            if (size == ((RedisString) other).size()) {
                for (int i = 0; i < size; i++) {
                    if (bytes[i] != otherBytes[i]) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    public String toString(){
        return new String(bytes,0,size());
    }

    public int cap(){return bytes.length;}

    public int size(){
        return size;
    }

    // 释放的时候要进行释放
    public void release(){
        if(isReleased){return;}// 避免重复释放

        if(RedisServer.isCurrentThread()){
            pool.releaseObject(this);
        }else{
            // 先放到一个并发数据结构里面,然后主线程再读
            pool.releaseInOtherThread(this);
        }
        // 需要清理size和hash
        size = 0;
        hash = 0;
    }

    public static int readInt(RedisString s,int pos){
        int ret = 0;
        for(int i = pos; i < pos + 4;++i){
            ret = ret * 255 + s.getByte(i);
        }
        return ret;
    }

    // 从RedisString里面拷贝一部分出来成为新的RedisString
    public static RedisString copyRedisString(RedisString s,int pos,int len){
        RedisString ret = RedisString.allocate(s,pos,len);
        return ret;
    }
}
