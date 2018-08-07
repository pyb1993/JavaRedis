package Common;

/* 辅助类 */
public class RedisUtil {

    // 因为key不可能为null,所以不需要检查
    // 只检查10进制的情况
    public static boolean isInteger(String s) {
        int len = s.length();
        if(len == 0) return false;
        if( s.charAt(0) == '-' && len == 1) {
            return false;
        }

        for(int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if(c > '9' || c < '0'){
                return false;
            }
        }
        return true;
    }


}
