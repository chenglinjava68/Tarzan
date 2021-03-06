/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.tongbanjie.tarzan.server;

import com.tongbanjie.tarzan.common.Constants;
import com.tongbanjie.tarzan.common.Service;
import com.tongbanjie.tarzan.common.util.NamedThreadFactory;
import com.tongbanjie.tarzan.common.util.NetworkUtils;
import com.tongbanjie.tarzan.registry.Address;
import com.tongbanjie.tarzan.registry.RecoverableRegistry;
import com.tongbanjie.tarzan.registry.ServerAddress;
import com.tongbanjie.tarzan.registry.zookeeper.ServerZooKeeperRegistry;
import com.tongbanjie.tarzan.rpc.RpcServer;
import com.tongbanjie.tarzan.rpc.netty.NettyRpcServer;
import com.tongbanjie.tarzan.rpc.netty.NettyServerConfig;
import com.tongbanjie.tarzan.rpc.protocol.RequestCode;
import com.tongbanjie.tarzan.server.client.ClientChannelManageService;
import com.tongbanjie.tarzan.server.client.ClientManager;
import com.tongbanjie.tarzan.server.processer.ClientManageProcessor;
import com.tongbanjie.tarzan.server.processer.QueryMessageProcessor;
import com.tongbanjie.tarzan.server.processer.RecordConsumeProcessor;
import com.tongbanjie.tarzan.server.processer.SendMessageProcessor;
import com.tongbanjie.tarzan.server.transaction.TransactionCheckService;
import com.tongbanjie.tarzan.store.StoreManager;
import com.tongbanjie.tarzan.common.util.DistributedIdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/**
 * 服务进程控制器 <p>
 * 〈功能详细描述〉
 *
 * @author zixiao
 * @date 16/9/29
 */
@Component
public class ServerController implements Service {

    // 服务器配置
    @Autowired
    private ServerConfig serverConfig;

    // 通信层配置
    private NettyServerConfig nettyServerConfig;

    // 服务注册中心
    private RecoverableRegistry serverRegistry;

    // 远程通信层对象
    private RpcServer rpcServer;

    // 客户端连接管理
    @Autowired
    private ClientManager clientManager;

    // 存储管理
    @Autowired
    private StoreManager storeManager;

    // 定时任务管理器
    @Autowired
    private ScheduledServiceManager scheduledServiceManager;

    // 事务状态检查服务
    @Autowired
    private TransactionCheckService transactionCheckService;

    // 消息重发服务
    @Autowired
    private MessageResendService messageResendService;

    // 检测所有客户端连接
    @Autowired
    private ClientChannelManageService clientChannelManageService;

    // 处理发送消息线程池
    private ExecutorService sendMessageExecutor;

    // 处理管理Client线程池
    private ExecutorService clientManageExecutor;

    // 服务器地址
    private ServerAddress serverAddress;

    public ServerController() {

    }

    public void initialize() {
        /**
         * 0、配置参数校验
         */
        if(serverConfig.getServerPort() < 1024 || serverConfig.getServerPort() > 65535){
            throw new ServerException(Constants.TARZAN_SERVER_PORT + " must be between 1024 and 65535.");
        }
        if (DistributedIdGenerator.validate(serverConfig.getServerId())) {
            throw new ServerException(Constants.TARZAN_SERVER_ID + " must be between 0 and "+ DistributedIdGenerator.getMaxWorkId());
        }

        /**
         * 1、初始化实例
         */
        this.nettyServerConfig = new NettyServerConfig(this.serverConfig.getServerPort());
        this.serverRegistry = new ServerZooKeeperRegistry(this.serverConfig.getRegistryAddress());
        this.rpcServer = new NettyRpcServer(this.nettyServerConfig, this.clientChannelManageService);

        /**
         * 2、注册请求处理器
         */
        this.registerProcessor();

        /**
         * 3、连接注册中心，并注册地址
         */
        this.registerServer();

        /**
         * 4、初始化定时任务
         */
        this.scheduledServiceManager
                .add(this.transactionCheckService)     //事务状态检查服务
                .add(this.messageResendService)        //消息重发Job
                .add(this.clientChannelManageService); //检测所有客户端连接

        /**
         * 5、设置分布式Id生成器的WorkId
         */
        DistributedIdGenerator.setUniqueWorkId(serverConfig.getServerId());
    }

    /**
     * 连接注册中心，并注册ServerId和地址
     * @return
     */
    private boolean registerServer() {
        //1、连接注册中心
        try {
            serverRegistry.start();
        } catch (Exception e) {
            throw new ServerException("The registry connect failed, address: " + serverConfig.getRegistryAddress(), e);
        }

        //2、获取服务器地址 [ip]:[port]
        String localIp = NetworkUtils.getLocalHostIp();
        if (localIp == null) {
            throw new ServerException("Get localHost ip failed.");
        }
        serverAddress = new ServerAddress(localIp, serverConfig.getServerPort(),serverConfig.getServerWeight());
        serverAddress.setServerId(serverConfig.getServerId());

        if (serverRegistry.isConnected()) {
            //3.1、注册serverId
            boolean registerFlag = ((ServerZooKeeperRegistry) this.serverRegistry)
                    .registerId(serverAddress.getServerId(), serverAddress);
            if (!registerFlag) {
                throw new ServerException("The server id '" + serverAddress.getServerId() +
                        "' already in use, it must be unique in cluster.");
            }
            //3.2、注册服务器地址
            serverRegistry.register(serverAddress);
            return true;
        }
        throw new ServerException("Register server failed, registry address: " + serverConfig.getRegistryAddress()
                + ", server id: " + serverAddress.getServerId());
    }

    /**
     * 注册请求处理器
     */
    private void registerProcessor() {
        this.sendMessageExecutor = new ThreadPoolExecutor(//
                this.serverConfig.getSendMessageThreadPoolNum(),//
                this.serverConfig.getSendMessageThreadPoolNum(),//
                1000 * 60,//
                TimeUnit.MILLISECONDS,//
                new LinkedBlockingQueue<Runnable>(this.serverConfig.getSendThreadPoolQueueCapacity()),//
                new NamedThreadFactory("SendMessageThread_"));

        this.clientManageExecutor = Executors.newFixedThreadPool(
                this.serverConfig.getClientManageThreadPoolNum(),
                new NamedThreadFactory("ClientManageThread_"));

        SendMessageProcessor sendProcessor = new SendMessageProcessor(this);
        this.rpcServer.registerProcessor(RequestCode.SEND_MESSAGE, sendProcessor, this.sendMessageExecutor);
        this.rpcServer.registerProcessor(RequestCode.TRANSACTION_MESSAGE, sendProcessor, this.sendMessageExecutor);

        QueryMessageProcessor queryProcessor = new QueryMessageProcessor(this);
        this.rpcServer.registerProcessor(RequestCode.QUERY_MESSAGE, queryProcessor, this.sendMessageExecutor);

        ClientManageProcessor clientProcessor = new ClientManageProcessor(this);
        this.rpcServer.registerProcessor(RequestCode.HEART_BEAT, clientProcessor, this.clientManageExecutor);
        this.rpcServer.registerProcessor(RequestCode.UNREGISTER_CLIENT, clientProcessor, this.clientManageExecutor);
        this.rpcServer.registerProcessor(RequestCode.HEALTH_CHECK, clientProcessor, this.clientManageExecutor);

        RecordConsumeProcessor recordConsumeProcessor = new RecordConsumeProcessor(this);
        this.rpcServer.registerProcessor(RequestCode.RECORD_CONSUME, recordConsumeProcessor, this.sendMessageExecutor);
    }

    public void start() throws Exception {
        if (this.storeManager != null) {
            this.storeManager.start();
        }

        if (this.rpcServer != null) {
            this.rpcServer.start();
        }

        if(this.scheduledServiceManager != null){
            this.scheduledServiceManager.start();
        }

    }

    public void shutdown() {
        if (this.serverRegistry != null){
            this.serverRegistry.shutdown();
        }

        if (this.rpcServer != null) {
            this.rpcServer.shutdown();
        }

        if (this.sendMessageExecutor != null) {
            this.sendMessageExecutor.shutdown();
        }
        if (this.clientManageExecutor != null) {
            this.clientManageExecutor.shutdown();
        }

        if (this.storeManager != null) {
            this.storeManager.shutdown();
        }

        if (this.scheduledServiceManager != null) {
            this.scheduledServiceManager.shutdown();
        }

    }

    public ServerConfig getServerConfig() {
        return serverConfig;
    }

    public ClientManager getClientManager() {
        return clientManager;
    }

    public RecoverableRegistry getServerRegistry() {
        return serverRegistry;
    }

    public StoreManager getStoreManager() {
        return storeManager;
    }

    public RpcServer getRpcServer() {
        return rpcServer;
    }

    public Address getServerAddress() {
        return serverAddress;
    }

    public ExecutorService getSendMessageExecutor() {
        return sendMessageExecutor;
    }

}
