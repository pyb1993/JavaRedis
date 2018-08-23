package RedisDataBase;

import io.netty.util.CharsetUtil;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Random;

/**
 * HyperLogLog2 的数据结构
 * 统计原理:
 *  令K 为n次投掷里面最大的投掷次数
 *  P(A) = 所有投掷次数X_i <= K = (1 - (1/2)^k)^n
 *  P(B) = 不是所有投掷次数X_i 都小于K(因为至少有一个达到了k) = 1 - (1 - (1/2)^(k-1))^n
 *
 *  注意 A,B 是同时发生的,因此P(A)和P(B)就不应该接近0,否则和「小概率事件不发生的假设」矛盾
 *  容易得到  n ~= 2^k
 *
 * 分桶进行统计:
 *  分桶的意义:
 *      为了避免出现只投了一次就出现很多正面的情况,这会造成偶然误差很大,所以通过分桶来减小误差
 *      随着实验次数的增多,这里的误差就会减小,但是如果只实验几次,即便分桶也无济于事,很可能造成误差很大
 *      比如一共就只实验10次,完全有可能造成里面有一半的桶误差都很大的情况
 *
 *
 *
 *
 * **/
public class HyperLogLog {
    static int Num = 1 << 14;
    static HashMap<Long,Integer> table;
    static double reciprocal[] = new double[65];// 用来存储1到50的倒数
    static MessageDigest mD5Hasher;

    byte buckets[];
    long card;// 用来缓存上一次基数的数量

    static {
        table = new HashMap<Long,Integer>();
        table.put(0L,64);
        for(int i = 0; i < 64; ++i){
            table.put(1L << i,i);
        }

        // 存储第一次出现1的bit的位置的倒数
        for(int i = 1; i <= 64; ++i){
            reciprocal[i] = (1.0d / (1L << i));
        }

        //initialize
        try{
            mD5Hasher = MessageDigest.getInstance("MD5");
        }catch (Exception e) {
        }

    }

    public HyperLogLog(){
        buckets = new byte[Num];
        card = 0;
    }

    // 加入一个
    public void pfadd(String val){
        long h = hash(val);
        int index = (int)(h & (Num - 1));
        int zeroBits = zeroBitCount((h & 0xffffffffffffc000L) >> 14);// 前50位
        updateMax(index,zeroBits);
        card = -1; // 让基数缓存失效
    }

    // 获取基数结果
    public long pfcount(){
        if(card < 0){
            card = estimateForHLL();
        }
        return card;
    }

    // 计算估计的基数,利用调和平均数进行计算
    public long estimateForHLL(){
        double sumbitsInverse = 0;
        int validNum = 0;// 统计不为0的桶
        for(int i = 0; i < Num; i++){
            int bucketEstimate = buckets[i];// 连续0的数量+1,第一次出现1的index
            if(bucketEstimate != 0){
                sumbitsInverse += reciprocal[bucketEstimate];// 2 ^ -i
                validNum++;
            }
        }
        if(validNum < Num * 0.05) {
            // 如果数量很少就直接计数
            return validNum;
        }

        int ez = Num - validNum;// 0的数量
        sumbitsInverse += ez;// 当ez很大的时候进行的补偿(具体推导不明)
        double zl = Math.log(ez + 1);
        double beta = -0.370393911 * ez +
                0.070471823 * zl +
                0.17393686 * Math.pow(zl,2) +
                0.16339839 * Math.pow(zl,3) +
                -0.09237745 * Math.pow(zl,4) +
                0.03738027 * Math.pow(zl,5) +
                -0.005384159 * Math.pow(zl,6) +
                0.00042419 * Math.pow(zl,7);


        double alpha =  0.7213 / (1 + 1.079 / Num );
        double ret = (alpha * Num * validNum * (1 / (sumbitsInverse + beta)));
        return (long)ret;
    }

    // 保持这个桶里面最大的元素
    // 注意这里需要加1,表示第一次出现1的位置
    void updateMax(int index,int zeroBits){
        if(buckets[index] < zeroBits + 1){
            buckets[index] = (byte)(zeroBits + 1);
        }
    }

    /* 获取一个整数末尾有多少连续的0
    *  1 计算 x = (val ^ (val - 1)) & val
    *  2 x = 2 ^ i, i 就是末尾连续是0的个数,i最多等于50
    *  3 查表得 i = table[x]
    *
    * */
    int zeroBitCount(long val){
        long x = (val ^ (val - 1)) & val;
        int ret = table.get(x);
        return ret;
    }



    // 加上一些随机MagicNumber,目的是避免 0,1,2,3,4这样的字符串无法得到有效随机
    // 保证结果是正数
    long hash(String val){
        ByteBuffer buffer = ByteBuffer.wrap(mD5Hasher.digest( (val).getBytes(CharsetUtil.UTF_8)));
        return buffer.getLong();
    }

}
