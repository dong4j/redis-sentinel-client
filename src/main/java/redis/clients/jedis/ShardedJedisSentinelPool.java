package redis.clients.jedis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.util.Hashing;
import redis.clients.util.Pool;

/**
 * "sentinel" 模式,分片连接池
 * 
 * 
 * @author hailin1.wang@yeah.net
 * @createDate 2016年7月15日
 * 
 */
public class ShardedJedisSentinelPool extends Pool<ShardedJedis> {

    /**
     * 
     */
    private final Logger log = Logger.getLogger(getClass().getName());

    /**
     * 配置信息
     */
    private GenericObjectPoolConfig poolConfig;

    /**
     * sentinel 监听器，订阅sentinel集群上master变更的消息
     */
    private Set<MasterListener> masterListeners;

    /**
     * 本地master路由表
     * 
     */
    private volatile Map<String, HostAndPort> localMasterRoutingTable;

    /**
     * 从sentinel获取master地址出错的重试次数
     */
    private int retrySentinel = 5;

    private int connectionTimeout;
    private int soTimeout;
    private int database;
    private String password;

    /**
     * 
     * @param masters
     * @param sentinels
     */
    public ShardedJedisSentinelPool(List<String> masters, Set<String> sentinels) {
        this(masters, sentinels, new GenericObjectPoolConfig());
    }

    public ShardedJedisSentinelPool(List<String> masters, Set<String> sentinels,
            final GenericObjectPoolConfig poolConfig) {
        this(masters, sentinels, poolConfig, Protocol.DEFAULT_TIMEOUT);
    }

    public ShardedJedisSentinelPool(List<String> masters, Set<String> sentinels,
            final GenericObjectPoolConfig poolConfig, int soTimeout) {
        this(masters, sentinels, poolConfig, soTimeout, 5);
    }

    public ShardedJedisSentinelPool(List<String> masters, Set<String> sentinels,
            GenericObjectPoolConfig poolConfig, int soTimeout, int retrySentinel) {
        this(masters, sentinels, poolConfig, soTimeout, retrySentinel, Protocol.DEFAULT_TIMEOUT,
                null, Protocol.DEFAULT_DATABASE);
    }

    public ShardedJedisSentinelPool(List<String> masters, Set<String> sentinels,
            final GenericObjectPoolConfig poolConfig, int soTimeout, int retrySentinel,
            int connectionTimeout, final String password, final int database) {
        this.poolConfig = poolConfig;
        this.soTimeout = soTimeout;
        this.retrySentinel = retrySentinel;
        this.connectionTimeout = connectionTimeout;
        this.password = password;
        this.database = database;
        this.masterListeners = new HashSet<MasterListener>(sentinels.size());

        Map<String, HostAndPort> newMasterRoutingTable = initSentinels(sentinels, masters);
        initPool(newMasterRoutingTable);
    }

    /**
     * 根据newMasterRoutingTable路由表信息初始化连接池
     * 
     * @param newMasterRoutingTable
     */
    private void initPool(Map<String, HostAndPort> newMasterRoutingTable) {
        if (!equals(localMasterRoutingTable, newMasterRoutingTable)) {
            List<JedisShardInfo> shardMasters = makeShardInfoList(newMasterRoutingTable);
            initPool(poolConfig, new ShardedJedisFactory(shardMasters, Hashing.MURMUR_HASH, null));
            localMasterRoutingTable = newMasterRoutingTable;
        }
    }

    /**
     * 获取ShardedJedis客户端
     * 
     */
    @Override
    public ShardedJedis getResource() {
        ShardedJedis jedis = super.getResource();
        jedis.setDataSource(this);
        return jedis;
    }

    /**
     * 为了兼容老代码调用，直接从ShardedJedisPool中拷贝而来。
     * 
     * @deprecated starting from Jedis 3.0 this method will not be exposed. Resource cleanup should be done using @see
     *             {@link redis.clients.jedis.Jedis#close()}
     */
    @Override
    @Deprecated
    public void returnBrokenResource(final ShardedJedis resource) {
        if (resource != null) {
            returnBrokenResourceObject(resource);
        }
    }

    /**
     * 为了兼容老代码调用，直接从ShardedJedisPool中拷贝而来。
     * 
     * @deprecated starting from Jedis 3.0 this method will not be exposed. Resource cleanup should be done using @see
     *             {@link redis.clients.jedis.Jedis#close()}
     */
    @Override
    @Deprecated
    public void returnResource(final ShardedJedis resource) {
        if (resource != null) {
            resource.resetState();
            returnResourceObject(resource);
        }
    }

    /**
     * close
     */
    public void destroy() {
        for (MasterListener m : masterListeners) {
            m.shutdown();
        }
        super.destroy();
    }

    /**
     * 本地路由表与新路由表对比
     * 
     * @param localMasterRoutingTable
     * @param masterRoutingTable
     * @return
     */
    private boolean equals(Map<String, HostAndPort> localMasterRoutingTable,
            Map<String, HostAndPort> newMasterRoutingTable) {
        if (localMasterRoutingTable != null && newMasterRoutingTable != null) {
            if (localMasterRoutingTable.size() == newMasterRoutingTable.size()) {
                List<HostAndPort> localMasterValue = toHostAndPort(localMasterRoutingTable);
                List<HostAndPort> newmasterValue = toHostAndPort(newMasterRoutingTable);
                for (int i = 0, len = newmasterValue.size(); i < len; i++) {
                    if (!localMasterValue.get(i).equals(newmasterValue.get(i))) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 获取当前活跃地址
     * 
     * @return
     */
    private List<HostAndPort> getCurrentHostMaster() {
        return toHostAndPort(localMasterRoutingTable);
    }

    /**
     * 
     * @param routingTable
     * @return
     */
    private List<HostAndPort> toHostAndPort(Map<String, HostAndPort> routingTable) {
        return new ArrayList<HostAndPort>(routingTable.values());
    }

    /**
     * 
     * @param masterAddr
     * @return
     */
    private HostAndPort toHostAndPort(List<String> masterAddr) {
        String host = masterAddr.get(0);
        int port = Integer.parseInt(masterAddr.get(1));
        return new HostAndPort(host, port);
    }

    /**
     * 构造JedisShardInfo
     * 
     * @param newMasterRoutingTable
     * @return
     */
    private List<JedisShardInfo> makeShardInfoList(Map<String, HostAndPort> newMasterRoutingTable) {
        List<JedisShardInfo> shardMasters = new ArrayList<JedisShardInfo>();
        Set<Entry<String, HostAndPort>> entrySet = newMasterRoutingTable.entrySet();
        StringBuilder info = new StringBuilder();
        for (Entry<String, HostAndPort> entry : entrySet) {
            /**
             * 这个里带上master-name(entry.getKey())作为JedisShardInfo的name
             * <p>
             * 以便同一个master一致性hash时落在相同的点上,详情可参考
             */
            JedisShardInfo jedisShardInfo = new JedisShardInfo(entry.getValue().getHost(), entry
                    .getValue().getPort(), soTimeout, entry.getKey());
            jedisShardInfo.setPassword(password);
            shardMasters.add(jedisShardInfo);

            info.append(entry.getKey());
            info.append(":");
            info.append(entry.getValue().toString());
            info.append(" ");
        }
        log.info("Created ShardedJedisPool to master at [" + info.toString() + "]");
        return shardMasters;
    }

    /**
     * 初始化Sentinels，获取master路由表信息
     * 
     * @param sentinels
     * @param masters
     * @return
     */
    private Map<String, HostAndPort> initSentinels(Set<String> sentinels, final List<String> masters) {

        log.info("Trying to find all master from available Sentinels...");

        Map<String, HostAndPort> masterRoutingTable = new LinkedHashMap<String, HostAndPort>();

        for (String masterName : masters) {
            HostAndPort master = masterRoutingTable.get(masterName);
            // 当前master已初始化
            if (null != master) {
                continue;
            }

            boolean fetched = false;
            boolean sentinelAvailable = false;
            int sentinelRetryCount = 0;

            while (!fetched && sentinelRetryCount < retrySentinel) {
                for (String sentinel : sentinels) {
                    final HostAndPort hap = toHostAndPort(Arrays.asList(sentinel.split(":")));

                    log.fine("Connecting to Sentinel " + hap);

                    try {
                        Jedis jedis = new Jedis(hap.getHost(), hap.getPort());
                        // 从sentinel获取masterName当前master-host地址
                        List<String> masterAddr = jedis.sentinelGetMasterAddrByName(masterName);
                        // connected to sentinel...
                        sentinelAvailable = true;

                        if (masterAddr == null || masterAddr.size() != 2) {
                            log.warning("Can not get master addr, master name: " + masterName
                                    + ". Sentinel: " + hap + ".");
                            continue;
                        }

                        // 将masterName-master存入路由表
                        master = toHostAndPort(masterAddr);
                        log.fine("Found Redis master at " + master);

                        masterRoutingTable.put(masterName, master);
                        fetched = true;
                        jedis.disconnect();
                        break;
                    } catch (JedisConnectionException e) {
                        log.warning("Cannot connect to sentinel running @ " + hap
                                + ". Trying next one.");
                    }
                }

                if (null == master) {
                    try {
                        if (sentinelAvailable) {
                            // can connect to sentinel, but master name seems to not
                            // monitored
                            throw new JedisException("Can connect to sentinel, but " + masterName
                                    + " seems to be not monitored...");
                        } else {
                            log.severe("All sentinels down, cannot determine where is "
                                    + masterName
                                    + " master is running... sleeping 1000ms, Will try again.");
                            Thread.sleep(1000);
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    fetched = false;
                    sentinelRetryCount++;
                }
            }

            // Try sentinelRetry times.
            if (!fetched && sentinelRetryCount >= retrySentinel) {
                log.severe("All sentinels down and try " + sentinelRetryCount + " times, Abort.");
                throw new JedisConnectionException("Cannot connect all sentinels, Abort.");
            }
        }

        log.info("Redis master running at " + masterRoutingTable.size()
                + ", starting Sentinel listeners...");
        for (String sentinel : sentinels) {
            final HostAndPort hap = toHostAndPort(Arrays.asList(sentinel.split(":")));
            MasterListener masterListener = new MasterListener(masters, hap.getHost(),
                    hap.getPort());
            // whether MasterListener threads are alive or not, process can be stopped
            masterListener.setDaemon(true);
            masterListeners.add(masterListener);
            masterListener.start();
        }

        return masterRoutingTable;
    }

    /**
     * 
     * master监听器，从sentinel订阅master变更的消息
     * 
     * @author hailin1.wang@downjoy.com
     * @createDate 2016年7月15日
     * 
     */
    protected class MasterListener extends Thread {

        protected List<String> masters;
        protected String host;
        protected int port;
        protected long subscribeRetryWaitTimeMillis = 5000;
        protected volatile Jedis j;
        protected AtomicBoolean running = new AtomicBoolean(false);

        protected MasterListener() {
        }

        public MasterListener(List<String> masters, String host, int port) {
            super(String.format("MasterListener-%s-[%s:%d]", masters, host, port));
            this.masters = masters;
            this.host = host;
            this.port = port;
        }

        public MasterListener(List<String> masters, String host, int port,
                long subscribeRetryWaitTimeMillis) {
            this(masters, host, port);
            this.subscribeRetryWaitTimeMillis = subscribeRetryWaitTimeMillis;
        }

        public void run() {
            running.set(true);
            while (running.get()) {
                j = new Jedis(host, port);
                try {
                    // 订阅变更
                    j.subscribe(new MasterChengeProcessor(this.masters, this.host, this.port),
                            "+switch-master");
                } catch (JedisConnectionException e) {
                    if (running.get()) {
                        log.severe("Lost connection to Sentinel at " + host + ":" + port
                                + ". Sleeping 5000ms and retrying.");
                        try {
                            Thread.sleep(subscribeRetryWaitTimeMillis);
                        } catch (InterruptedException e1) {
                            e1.printStackTrace();
                        }
                    } else {
                        log.fine("Unsubscribing from Sentinel at " + host + ":" + port);
                    }
                }
            }
        }

        public void shutdown() {
            try {
                log.fine("Shutting down listener on " + host + ":" + port);
                running.set(false);
                // This isn't good, the Jedis object is not thread safe
                if (j != null) {
                    j.disconnect();
                }
            } catch (Exception e) {
                log.log(Level.SEVERE, "Caught exception while shutting down: ", e);
            }
        }
    }

    /**
     * 
     * 当master变更时接收消息处理
     * 
     * @author hailin1.wang@downjoy.com
     * @createDate 2016年7月15日
     * 
     */
    protected class MasterChengeProcessor extends JedisPubSub {

        protected List<String> masters;
        protected String host;
        protected int port;

        /**
         * @param masters
         * @param host
         * @param port
         */
        public MasterChengeProcessor(List<String> masters, String host, int port) {
            super();
            this.masters = masters;
            this.host = host;
            this.port = port;
        }

        /*
         * (non-Javadoc)
         * 
         * @see redis.clients.jedis.JedisPubSub#onMessage(java.lang.String, java.lang.String)
         */
        @Override
        public void onMessage(String channel, String message) {
            masterChengeProcessor(channel, message);
        }

        /**
         * master变更消息处理
         */
        private void masterChengeProcessor(String channel, String message) {

            /**
             * message格式：master-name old-master-host old-master-port new-master-host new-master-port
             * <p>
             * 示例：master1 192.168.1.112 6380 192.168.1.111 6379
             */
            log.fine("Sentinel " + host + ":" + port + " published: " + message + ".");
            String[] switchMasterMsg = message.split(" ");

            if (switchMasterMsg.length > 3) {

                int index = masters.indexOf(switchMasterMsg[0]);
                /**
                 * 因sentinel集群能同时管理多组master-slave,故只处理当前工程配置的master变更
                 * <p>
                 * 当前变更的master（switchMasterMsg[0]）必须是被包含在工程配置中。
                 * 
                 */
                if (index != -1) {
                    HostAndPort newHostMaster = toHostAndPort(Arrays.asList(switchMasterMsg[3],
                            switchMasterMsg[4]));
                    Map<String, HostAndPort> newMasterRoutingTable = new LinkedHashMap<String, HostAndPort>();
                    // 拷贝原有的本地路由表
                    newMasterRoutingTable.putAll(localMasterRoutingTable);
                    // 设置变更信息
                    newMasterRoutingTable.put(switchMasterMsg[0], newHostMaster);

                    // 重新初始化整个pool
                    initPool(newMasterRoutingTable);
                } else {
                    StringBuilder info = new StringBuilder();
                    for (String masterName : masters) {
                        info.append(masterName);
                        info.append(",");
                    }
                    log.fine("Ignoring message on +switch-master for master name "
                            + switchMasterMsg[0] + ", our monitor master name are [" + info + "]");
                }
            } else {
                log.severe("Invalid message received on Sentinel " + host + ":" + port
                        + " on channel +switch-master: " + message);
            }
        }
    }

    /**
     * 
     * ShardedJedis生产工厂
     * 
     * @author hailin1.wang@downjoy.com
     * @createDate 2016年7月15日
     * 
     */
    protected class ShardedJedisFactory implements PooledObjectFactory<ShardedJedis> {
        private List<JedisShardInfo> shards;
        private Hashing algo;
        private Pattern keyTagPattern;

        public ShardedJedisFactory(List<JedisShardInfo> shards, Hashing algo, Pattern keyTagPattern) {
            this.shards = shards;
            this.algo = algo;
            this.keyTagPattern = keyTagPattern;
        }

        // 生产对象
        public PooledObject<ShardedJedis> makeObject() throws Exception {
            ShardedJedis jedis = new ShardedJedis(shards, algo, keyTagPattern);
            return new DefaultPooledObject<ShardedJedis>(jedis);
        }

        public void destroyObject(PooledObject<ShardedJedis> pooledShardedJedis) throws Exception {
            final ShardedJedis shardedJedis = pooledShardedJedis.getObject();
            for (Jedis jedis : shardedJedis.getAllShards()) {
                try {
                    try {
                        jedis.quit();
                    } catch (Exception e) {

                    }
                    jedis.disconnect();
                } catch (Exception e) {

                }
            }
        }

        // 默认验证方法
        public boolean validateObject(PooledObject<ShardedJedis> pooledShardedJedis) {
            try {
                ShardedJedis jedis = pooledShardedJedis.getObject();
                for (Jedis shard : jedis.getAllShards()) {
                    if (!shard.ping().equals("PONG")) {
                        return false;
                    }
                }
                return true;
            } catch (Exception ex) {
                return false;
            }
        }

        public void activateObject(PooledObject<ShardedJedis> p) throws Exception {

        }

        public void passivateObject(PooledObject<ShardedJedis> p) throws Exception {

        }
    }
}