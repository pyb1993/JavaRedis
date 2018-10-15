
### 这是一个用Netty实现的Redis服务器

### 目的有三:
1. 深入理解redis,将redis上的各种功能慢慢迁移到java上来
	用C语言来实现太麻烦了,而且一般工作中用不到C,正好练习一下Java
	同时降低开发难度,对于一些功能可以更轻松的实现
	未来可以用JNI将重要数据结构封装起来,提高性能(另开一个branch)
2. 理解现代服务器的网络/线程模型
	最近刚刚学习了Netty,顺便熟悉netty的使用
3. 可以用来学习分布式,实现简单的主从,集群功能
   学习分布式不可能只动眼不动手,所以以后可以这个项目为出发点开始魔改

---
### 目前实现的功能:

```
1. get set hget hset
```
---
### Version-1架构
因为使用了Netty,所以不需要过于操心网络,针对redis本身的特点(来往的数据量比较小,全是纯内存操作,其实cpu开销也不是很大),所以单次操作非常的快,这种情况下,cpu并不是瓶颈,往往网络先达到瓶颈(这里不是指网卡流量跑满,而是说处理各种链接的开销)
所以采取单线程(多线程带来的线程同步的开销相当大,暂时没有必要)
也就是 网络 + 业务 都在一个线程里面(一开始也尝试使用额外的线程池来执行,后面发现没有必要)

#### Handler
	用一个Map维护命令和Handler之间的关系,来一个命令就去查找对应的Handler,然后调用对应的handler
		
#### 编码/解码:
	先不考虑性能
	直接使用阿里的FastJson,用来序列化参数(command 对应的类型都利用一个hashMap提前注册好)
    
#### 协议格式	:      
	每个单元 长度 + bytes
	一共三个单元:
	requestId(用来校验)
	command(命令类型)
	content(结果,也是FastJson序列化的字节)
	

#### 数据库部分：
	在RedisDb.java文件里面,核心是一个 
	HashMap<String,RedisObject>
	
RedisObject定义如下:

```
class RedisObject{
	int type;
	Object data;
	.....
}	
```

####  实现了HyperLogLog数据结构：
##### 基本原理:
 
 *  令K 为n次投掷「硬币」面最大的投掷次数
 *  P(A) = 所有投掷次数<a href="https://www.codecogs.com/eqnedit.php?latex=$&space;P(X_i&space;<=&space;K)&space;=&space;(1&space;-&space;(1/2)^k)^n$" target="_blank"><img src="https://latex.codecogs.com/gif.latex?$&space;P(X_i&space;<=&space;K)&space;=&space;(1&space;-&space;(1/2)^k)^n$" title="$ P(X_i <= K) = (1 - (1/2)^k)^n$" /></a>
 *  P(B) = 不是所有投掷次数X_i 都小于K(因为至少有一个达到了k) = <a href="https://www.codecogs.com/eqnedit.php?latex=\bg_black&space;\large&space;$&space;1&space;-&space;(1&space;-&space;(1/2)^{k-1})^n&space;$" target="_blank"><img src="https://latex.codecogs.com/gif.latex?\bg_black&space;\large&space;$&space;1&space;-&space;(1&space;-&space;(1/2)^{k-1})^n&space;$" title="\large $ 1 - (1 - (1/2)^{k-1})^n $" /></a>
 *  注意 A,B 是同时发生的,因此P(A)和P(B)就不应该接近0,否则和「小概率事件不发生的假设」矛盾
 *  容易得到 n  约等于<a href="https://www.codecogs.com/eqnedit.php?latex=\large&space;$&space;2^K&space;$" target="_blank"><img src="https://latex.codecogs.com/gif.latex?\large&space;$&space;2^K&space;$" title="\large $ 2^K $" /></a>

 * 分桶进行统计:
 *      为了避免出现只投了一次就出现很多正面的情况,这会造成偶然误差很大,所以通过分桶来减小误差
 *      随着实验次数的增多,这里的误差就会减小,但是如果只实验几次,即便分桶也无济于事,很可能造成误差很大
 *      比如一共就只实验10次,完全有可能造成里面有一半的桶误差都很大的情况
 *     实际上Redis本身做了很多优化,特别是利用一个关于没有投中桶的次数$ez$的函数进行拟合

#####  实现步骤:
1. 对字符串进行hash,这里使用`MD5`进行hash,然后获取前64位作为一个long用来进行标志
2. 取后14位作为桶的索引,取前50位作为投掷的序列,这里曾经犯了一个错误:
		

```
. 利用 H 的后14位作为index,然后计算整个H的zeroBits,注意这个时候没有使用Hash的后50位,从而导致bug:
  注意到: 由于bits大于14的概率很小,
  所以大多数时候实验的结果都是由后面14位决定的,
  这就导致同一个index上面的,bit几乎都是一样的
  完全失去了随机的意义	
```

3. 计算低位连续为0的个数(这里是对投掷次数进行建模),计算方式如下:
	* 获取一个整数末尾有多少连续的0
	*  1 计算 `x = (val ^ (val - 1)) & val`
	*  2 `x = 2 ^ i`, i 就是末尾连续是0的个数,i最多等于50
	*  3 查表得 `i = table.get(x)`(可能还有其它的方法,比如二分移位进行试探,由于这里x**绝大多数**情况下都是0或者1,所以可以从1开始试探)
	*  4 将 `i + 1`和原来桶里面的值取大者保留, 这是为了计算1出现的位置(实际上等于至少投掷一次)
	*  5 算count的时候计算`调和平均数`,这是为了避免单个离散值导致的异常

##### 实现:

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
##### 实际上这几个参数一个都不能少,否则误差就会变大(原理和实现还是差距很大的,具体推导感兴趣的自己取看paper,redis源码里面给了paper的名字)
---
### Version-2架构
	增加了几个重要功能:
	1 实现了异步线程辅助完成删除过期key
	2 删除过期key使用定时轮+最小堆+随机删除
	3 实现了普通字典和支持并发的字典的切换

#### <li> 同步删除的策略:
 我们对可能的使用模型分成两种:
  1 大量的短时间的过期(分布式锁)
  2 大量长时间的过期(普通的cache应用)
  3 1 2 的结合 
	<li>对于 1,我们可以使用一个定时轮+最小堆的结构来实现,首先核心数据结构是:
   

	 static PriorityList[] expires;
	 PriorityList则是对优先队列的封装
	这样每来一个过期事件,我们就判断过期时间是不是小于一个规定的时间,比如2个小时;
	如果小于就可以直接塞到对应的`PriorityList`里面,具体：
	
	            if(delay < slotNum * 4){
	            /* 如果是那种延迟很大的过期key,就不放在定时轮里面,而是采用原来redis的随机抽样做法
	            *  因为如果过期时间很长,就会导致定时轮里面存储太多数据,从而导致插入变慢,而且占用太多内存太久
	            */
	            int index = (int)((now + delay ) & (slotNum - 1));// 计算应该放到的index
	            PriorityList slot = expires[index];
	            slot.add(new ExpireObject(key,now + delay));
	            System.out.println("add index");
	        }
    
<li>对于2,我们可以使用原来Redis的随机删除策略来实现,也就是每次从过期字典里面「随机」选取16个key,然后计算这些可以是不是过期,用抽样来判断整体过期key的情况。一个难点是:
	Java的hashMap不支持随机抽取,为了性能不可能把map变成array来临时操作,所以采取一个相对hack的办法:
	

    利用反射+Unsafe,拿到HashMap里面对应的table(Node[]),然后还是利用unsafe计算出Node的next元素,这样就可以像Redis一样进行遍历了,当然这不是一个好的办法,和JDK具体实现耦合在了一起,只是不得已的办法,最好的办法还是自己重写一个HashMap,不用JDK的那个。

#### <li> 异步删除的策略:
我们为了支持异步线程辅助一些工作,比如说帮忙删除过期key,帮忙进行扩容等操作,设计了这样的策略:
		
```
从RedisHashMap衍生两种类型,第一个是普通的HashMap,另外一个是支持并发的HashMap
如果需要异步线程的支持,我们就在主线程里面将普通的Map替换成ConcurrentRedisHashMap
像这样:
	    public static void converMapToConcurrent(){
        if(!RedisMap.holdByAnother()) {
            RedisConcurrentHashMap<String,RedisObject> cmap = new RedisConcurrentHashMap<>(RedisMap);
            cmap.incrRef();
            RedisMap = cmap;
            //RedisMap.get("1");
        }
    }

同时注意到异步线程对于PriorityList的add操作和主线程过期操作可能会有数据竞争,所以我们需要单独为一个PrioirtyList设置一个并发的版本,思路类似上面的Map,具体内容见:「ConcurrentPriorityList.class」
```
##### 上面这样做,第一是对原来的RedisMap进行了封装,但是并没有对原有的数据操作,所以无论数据有多少,都是O(1)的操作

##### 第二,所有转换都在主线程里面执行,所以不需要考虑转换过程带来的线程安全的问题

##### 第三,转换完成之后,就可以提交任务,让异步线程也对该RedisHashMap进行操作了,这个时候操作一定是线程安全的

##### 第四,一般这种操作竞争的程度并不高(一共就几个异步线程进行操作)所以性能损失不大,而且每个操作很迅速,所以自己写了一个简单的spin-lock用来进行保护即可,具体内容见`RedisConcurrentHashMap.class`

#### #最后,我们只需要在异步线程结束之后,在主线程将状态转换回来即可,这种情况下只能让主线程去轮训提交的异步任务的future,只要future完成,就执行对应的回调,具体内容见`「ExpireFuture.java」`部分  

此外,还有一些小的优化,这里就不一一赘述了。
----
#### Version-3
##### 改动1:  增加了对于渐进式rehash的支持,为了达到这个目的,我重写了RedisHashMap(其实就是把JDK源码抄了一部分过来)
##### 改动2:  增加了异步线程进行rehash的支持(重点是确保线程安全,使得删除过期,rehash,以及主线程rehash协作运行)
---
### 思路设计
#### <li> 渐进rehash的支持
		实际上,最开始我使用的是JDK的HashMap,但是存在各种问题,你要在主动rehash的过程中防止hashMap自动扩容,要处理size的线程不安全,还要面对无法高效率的获取随机的值等问题。
		要绕过JDK的限制非常麻烦,而且关于自动扩容的问题, 非常难以限制,会导致线程安全的问题
		比如异步线程往新的map添加元素,同时主线程也将新的元素添加到·新的map,如果并发够大,
		完全可能产生新的map在扩容过程中再次扩容,这个时候导致分段锁失效等情况。
		
##### <li> 主线程渐进式rehash的逻辑
	put: 只往新的map存,但是同时要删除老的map里面可能的key
	remove: 先删老的map,再删新的map(否则存在线程安全)
	get: 先查新的map,后查老的map  
	什么时候执行这个逻辑:
		分两种情况来讨论,首先考虑扩容,这里把负载因子1作为分界线
		然后考虑缩容,这里把负载因子0.5作为分界限(缩容的时候还要考虑频率,限定为至少间隔1s)
		另外对于size比较小的rehash,就直接在主线程执行

		put的代码:
		 if(RedisServer.isCurrentThread()){
		            safePut(key,val);
		        }else{
		            if(!inRehashProgress()){
		                // 不扩容状态
		                greadLock();
		                safePut(key, val);
		                greadUnLock();
		            }else{
		                // 这里处于扩容状态， rehashMap != null
		                // 但是由于没有锁的保护,可能突然变成非扩容状态,rehashMap == NULL
		                // 所以 stopRehash只能在「没有异步线程的」
		                safePut(key,val);
		            }
		        }
		
		        // 这里有多种case 1 主线程扩容,当前字典可能是「普通状态」／「并发状态」
        // 2 异步线程扩容,且当前字典已经是扩容的状态
        // 3 异步线程扩容,当前字典一定是并发状态
        // 我们只在主线程切换状态,所以其它两个线程我们不执行
        if(RedisServer.isCurrentThread() && needGrowth(true)){
            startRehash(); // case2会直接被startRehash过滤
        }

##### <li> 异步线程的逻辑:
	遍历所有老的slot,将这些slot添加到新的map上面
	关键点: 每一个slot都需要加锁来保证正确性
	＜/br＞
	 EasyLock lock = lockArr[index & (lockNum - 1)];
        lock.lock(); // 需要lock住固定的index
        Node node = map.table[index];
        int nodeNum = 0;
        while (node != null){
            // 进行rehash
            ++nodeNum;
            rehashMap.put(node.getKey(),node.getValue());// 自动加锁
            node = node.next;
        }
        map.table[index] = null;//所有的都设置为null,这样就成功将map本身给解决了,map本身的size也应该修改
        tmpSize.addAndGet(-nodeNum);
        lock.unlock();
       ＜/br＞ 
##### <li> 线程安全和性能的讨论
		  首先我们引入了一个新的中间层 `RedisDict`
		  这个东西负责 rehash操作和 普通RedisHashMap到RedisConcurrentHashMap的转换,这样对于使用RedisDict的操作就完全屏蔽了。
		  但是,对于rehash操作,由于存在突然变成rehash状态和突然从rehash状态结束两种变化,那么这个线程安全是必须要保证的。

---
如果直接加锁,那么就会导致性能很差,因为处于并发状态和rehash状态的时间很少,如果每次检查是否处于rehash状态的时候都要加锁, 那么性能未免也太低了。一个做法是「double check」的思路,回想单例模式里面的常见pattern实现,可以避免大部分情况下进行加锁. 但是这里存在一个问题,采取这种模式必须保证状态的变化是「**单向**」的,比如说单例里面状态不能「从有到无」。但是rehash的变化是双向的状态,那么怎么做?

----
	两种策略: 1 延迟stopRehash的操作,所有的stopRehash操作只在没有异步线程持有当前RedisDict的时候才执行,这样就避免了上面说的问题。
				2 先检查是不是在主线程执行的这个操作,如果在主线程就不需要对检查 rehash状态加锁,因为所有对状态变化只发生在主线程。
	再看一次put的代码,这里采取策略2:
	
	 void safePut(K key, T val){
	        if(inRehashProgress()){
	            // 扩容状态
	            rehashMap.put(key,val);
	            map.remove(key);// 这一步是必要的
	        }else {
	            // 非扩容状态
	            map.put(key,val);
	        }
	    }

	if(RedisServer.isCurrentThread()){
		            safePut(key,val);
		        }else{
		           greadLock();
		            if(!inRehashProgress()){
		                // 不扩容状态
		                safePut(key, val);
		             }else{
		                // 这里处于扩容状态， rehashMap != null
		                // 但是由于没有锁的保护,可能突然变成非扩容状态,rehashMap == NULL
		                // 所以 stopRehash只能在「没有异步线程的」
		                safePut(key,val);
		            }
		             greadUnLock();
		        }
分析:首先判断是不是在主线程,如果在主线程,那么就代表不用担心状态突然发生变化,因为这是因为所有的状态变化都在「主线程执行」,所以不存在其它线程的干扰。
	如果不是在主线程,那么就需要加一个读锁,这是为了防止状态改变.

----
来看看remove,这里采取来策略1来实现(其实可以把策略2也加入进来,但是没有必要搞那么麻烦,因为本来都是小概率情况)	

	 public void remove(K key) {
        map.remove(key);
        if(inRehashProgress()) {// 1
            greadLock();
            if(inRehashProgress()){
                rehashMap.remove(key);
            }
            greadUnLock();
        }

        if(RedisServer.isCurrentThread() && needtrim(true)){
            startRehash();
        }
    }    
 ----
#### <li>  锁的实现
这里有两种锁,一种是自旋锁,一种是「读写自旋锁」,后者是为了保护「rehash状态」的,在改变rehash状态的时候要加上「writeLock」,检查的时候加上「readLock」.
因为这里的临界操作都非常短,所以全部用自旋的形式实现,而且不考虑公平性(一般0.1ms都不要就执行完了,所以尽可能的高效率)

---

#### <li> 状态改变的条件
	由于map具有这样几个属性,「是否并发」,「是否正在扩容」,「被几个线程持有」,其中「被其它线程持有」一定意味这「处于并发状态」
	所以停止rehash的时候,需要考虑一些情况,比如
	如果被其它线程持有着(并发状态),那么不能立刻结束rehash状态,这本来是通过「读写自旋锁」来进行保证的, 但是我们可以再提高一点性能,如果该Dict被其它线程持有,那么会延后执行该Dict的stopRehash,这样就可以先执行其它任务而不是「自旋」
	
	在开始rehash的时候,也要根据「是否已经处于并发状态」,「是否可以直接执行rehash」
	
	死锁,注意到获取size的时候同样存在线程安全的问题,但是size加的是「readLock」,如果调用的上层恰好使用「writeLock」,就会导致死锁。所以还需要一个无锁版本的size

---
####  Version-4
##### 改动:  针对GC频繁的问题,将最常用的数据结构String改造为RedisString,并且实现了一套对象池的技术,手动管理很多对象的分配,释放,统计信息等策略;

##### 首先说明一下idea的来源,由于运行的时候发现GC在高负载的时候非常的频繁,差不多1s就会产生一次GC,所以思考怎么解决这个问题;
##### 思路如下: 首先我们需要知道GC频繁的原因,因为每一次请求都会生成大量的临时对象:
		

    1 requestId 2 type 3 content
    然后 content又会根据命令的类型,被解析成 key 和 其它参数,中间的一些复制过程都会产生很多新的String
      
    2 hash里面的entry对象,这个对象只有在有新数据加入的时候才会被生成

##### 那么将新生代扩大是不是能够解决这个问题呢? 

答案是不能,因为cache类型的应用本质上大部分对象就是会存储很久的,从这个主线上来说老年代应该尽可能的大才对;
所以思考得出,那么只可能尽量减少这些临时对象的生成;
进一步的,String本身是immutable的,这个特点有数不清的好处,但是在这里不适用,第一导致无法像redis那样对字符串自己进行操作,第二,没有办法将对象池化(不能修改内容所以不能复用)


##### 这就得出一个结论,必须将所有的String都替换成一个我们自己设计的,可以池化的类型: RedisString
##### 这里实现的时候有这么一些难点:
<li>1 所有String都要替换,那么只能从netty bytebuf那里开始就不产生String,同时要实现在不new 新对象的情况下将RedisString从大对象拆分成小对象;另外不能使用String,那么fastJson也没有办法直接使用了,所以还需要考虑怎么自定义简单协议进行parse;
  
<li>2 RedisString本身是可以修改的,这确实也导致一些诡异的bug,根本原因就是还有对象在引用它,但是缺把它回收,这会导致各种奇怪问题: 死锁,对象被破坏.解析错误等等;所以分配和释放的时机要非常的小心才可以;
  	
<li> 3 分配的策略,对象池的分配算法比内存池简单,因为对象的大小一般是一组固定的大小;但是要考虑一些问题: 
对象的大小设置为多少合适? 
对象池的大小需要动态的变化,怎么设计变化的策略?
对象释放的时候有线程竞争,怎么保证性能和安全?
如何为一个分配的请求快速定位到合适的对象大小? 
如何设计一套合理的接口,将对象池本身逻辑抽象出来,这样实现其它的池化对象也相对简单清晰?

---
下面就简单描述一下解决了哪些问题,和解决的思路:
<li>1 替换所有String,体力活,要求是细心不出错,替换的时候还要重写hashcode,equal,parseInt等各种方法
  ＜/br＞
<li>2 如何设计一套接口体系,首先设计AbstractPooledObject这个抽象类,然后声明好length(),size(),newInstance(),release(),等抽象方法;然后设计一个AbstractObjectPool这样的抽象类,这个抽象类主要实现对象池,它具有很多组不同大小的对象container,分配的时候就迅速定位到合适大小的container,释放的时候也一样;如果找不到合适大小的container或者说没有对象可以分配,那么就调用newInstance来分配新的对象;
＜/br＞
<li>3 怎么设计策略? 
   ＜/br＞
```
	首先我们将对象池理解为一个管道,一边是流入的对象(release),一边是流出(allocate),那么我们取一段时间之内这两者的调和平均数作为对象池的大小,就可以比较好的适应需求; 所以需要进行统计,每次allocate的时候对申请对应大小的数组位置+1,release的时候也对对应大小的数组的位置+1; 这样就相当于统计一段时间之内分配次数和释放次数;
	 我们考虑这样一种情况: 将JavaRedis作为一个分布式的锁的实现,也就是set一下然后很快就释放掉;这种情况下中间有一个时间差delay,可以想到在delay结束之前,是不会有release被调用的,但是只要过了这个delay,那么就有数据被释放了,中间阶段都会达成一个平衡的状态;大概像下面一样:
		 allocate allocate ..................... allocate ........ allocate...
		[------ delay(3s)--------] release release ..... release ......		 
		当然中间可能存在一些波动,比如delay变化了,或者一段时间没有收到这样情况的请求;
	所以需要将统计的时间稍微扩大一点,变化平缓一点: 所以把这个数据当成一个时间序列我们进行加权:
	
allocateAccumulation[index] = allocateAccumulation[index] * scaleDown + allocateArray[index]
releaseAccumulation[index] = releaseAccumulation[index] * scaleDown + releaseArray[index]

	这样开启一个定时任务,每s运行一次,就可以得到过去一段时间里面 分配 和 释放的估计值,然后求调和平均数,得到这个大小下的对象池的估计大小;
	有了这个大小就可以实现各种策略了: 连续N次估计值大于threshold才分配对象,如果发现估计值大于当前的对象池大小,可以设计一套策略来调整大小;(比如大于原来的1.5倍就申请新的大小的对象池进行迁移)
	如果估计值太小,说明当前场景不适合对象池(分配对象也要开销的),所以可以适当减少对象池大小,连续N次这样就直接释放,但是
	如果又有新的分配需求,那么又满满将数据加回去(比如连续1024次不满足就释放对象池,设置这个值为-1024.如果有新的大量请求出现,那么就折半衰减负数,这样可以快速恢复到对象池分配的状态)
```

<li>4 怎么迅速定位?
	我们将设计对象池的长度为一系列长度 s1,s2,s3....sn,然后在中间插入 一些 2^n大小的数据,这样给定一个len,就利用JDK里面的办法先定位到第一个大于len的2^k,然后往回找一下,看哪个是第一个大于len且在我们初始化好的lengthTable里面,返回对应的index即可; 如果该池没有启用或者没有合适对象,可以尝试去更大的对象池找一下,但是不得超过所需的两倍大小;
	
	释放的时候也是一样的策略,关键是找到一个合适的长度s,使得 s <= len(注意这里和分配的要求恰恰相反,可以思考下为什么),
	然后尝试将数据返回对应的容器,如果满了就算了;

<li>5 怎么确保释放和分配线程安全？
	首先要考虑异步线程allocate和release会怎么样? 首先我们当然可以使用并发容器来控制分配或者加锁,但是这对性能的损耗比较大，所以一个办法是: 只在主线程进行对象池的分配,事实上目前的异步线程没有要分配对象的需求,只有释放的需求;
	释放的时候将数据放到一个并发队列里面,每过一段时间主线程就从这个队列统一将数据放到真正的对象池里面;
	这样就不会影响太多主线程分配和释放的性能;(还可以优化,就是用普通队列 lazyRemovedQue,但是异步线程每次放到一个线程私有的队列里面，然后定时将这个队列放入lazyRemovedQue,这样就只要加锁一次,然后主线程也只要加锁一次从lazyRemovedQue里面移动)
	另外为了性能,就只需要将这个对象池的容器使用数组实现的普通Queue即可(目前是手动实现一个,因为功能比较少,也可以用ArrayDeque)这样缓存更加友好;性能更好


<li>6 怎么保证一个线程里面release的时候是安全的?
首先明确问题是什么: 假设我有一个key(RedisString),这个key在什么时候应该被释放呢? 有这些场景:
	1 get请求,只读请求,显然可以释放
	2 set请求,如果这个ke已经重复了,那么可以直接释放,同时还可以释放老的value
难点在于: 我们如何保证这个RedisString释放的时候下面没有使用了? 比如如果现在处于	rehash的状态,那么
我们有这样的操作 map.put(key,val); rehashmap.remove(key); 那么如果你在RedisHashMap这个层次上操作,就没有办法知道
上层的情况(也不应该知道上层的调用逻辑); 有时候我们还利用key获取了EasyLock,如果你在里面就释放,会导致接下来获取的lock不正确从而产生死锁;
总结一下 Command -> RedisDb -> RedisDict -> RedisConcurrentHashMap -> RedisHashMap
					-> RedisHashMap															
ExpireHelper -> RedisDict也使用到了对应的调用链
我们要找到一个合适的位置释放该释放的东西,避免提前释放的错误;
＜/br＞
																
目前暂时先手动把逻辑写对,但是最好的做法将所有的释放集中到一个层面来管理:
＜/br＞
```
*      1 所有从CommnadHandler里面传入的key和value,我们设置一个isUsed的标记,如果这个key没有被使用过
*      那么就直接在RedisDb这个api的层面就进行释放;如果被使用了那么就不进行释放
*      这样对于那些只读操作的release管理就非常简单,对于set key,val这样的操作,如果key已经存在了,那么设置为isUsed为false即可
*
*      2 对于老的key,value,这个比较麻烦,因为在几个地方都有释放的规则,所以对于老的key,value需要我们将这个数据返回到最上层
*        这样就避免了依赖,同时在最上层进行释放;
*        另外有些地方直接调用了RedisDict而没有通过走RedisDb的接口,这个也比较麻烦,所以可能需要重构一下,所有对这两个map的操作都要走
*        RedisDb,否则容易出现release之后下面的逻辑又用到这个key的情况,较难管理	
```
