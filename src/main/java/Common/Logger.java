package Common;

public class Logger {
    public static int logLevel = 0;// 只有级别大于一个级别之下的才会初夏

    public static void log(String msg){
        System.out.println(msg);
    }

    public static void debug(String msg){
        if(logLevel >= 1) {
            System.out.println(msg);
        }
    }
}
