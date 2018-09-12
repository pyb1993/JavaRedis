package RedisDataBase;

import java.lang.reflect.Array;
import java.util.Arrays;

public class RedisStringPool extends AbstractObjectPool<RedisString>{
    @Override
    public void initLengthTable(){
    }

    // 用来在没有从池化数据里面获得数据的时候初始化
    @Override
    public RedisString newInstance(int len){
        return new RedisString(len);
    }

    // 因为String需要直接用byte初始化,所以单独写一个方法
    public RedisString getString(byte[] b){
        RedisString ret = allocate(b.length);
        ret.len = b.length;
        System.arraycopy(b,0,b,0,ret.len);
        // 将这个String拷贝过来
        return ret;
    }

}