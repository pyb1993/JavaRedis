package RedisDataBase;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.HashMap;

public class RedisStringPool extends AbstractObjectPool<RedisString>{
    // 这是因为范型数组没有办法在AbstractObjectPool里面初始化,所以不得不在子类确定类型之后进行
    class StringConatiner extends ObjectContainer{
        public StringConatiner(int len){
            super(len);
        }
    }

    public RedisStringPool(){
        super();
    }

    @Override
    public void initLengthTable(){
        // todo 实现长度表
        super.lengthTable = new int[]{8,32,64,128,256,512,1024,1536,2048,3092,4096,6114,8192};
        // todo 未来可以优化
        super.lenIndexMap = new HashMap<Integer, Integer>(){
            {
                for(int i = 0; i < lengthTable.length; ++i){
                    put(lengthTable[i],i);
                }
            }
        };
    }

    // 用来在没有从池化数据里面获得数据的时候初始化
    @Override
    public RedisString newInstance(int len){
        return new RedisString(len);
    }

    // 避免直接分配
    public RedisString getString(String s){
        byte[] bytes = s.getBytes();
        return getString(bytes,0,bytes.length);
    }
    public RedisString getString(RedisString s,int start,int size){
        return getString(s.bytes,start,size);
    }


    // 因为String需要直接用byte初始化,所以单独写一个方法
    public RedisString getString(byte[] b,int pos,int len){
        RedisString ret = allocate(len);
        ret.size = len;
        System.arraycopy(b,pos,ret.bytes,0,len);
        // 将这个String拷贝过来
        return ret;
    }

    @Override
    public void initObjectPool(){
        super.objectPool = new StringConatiner[lengthTable.length];
    }

}