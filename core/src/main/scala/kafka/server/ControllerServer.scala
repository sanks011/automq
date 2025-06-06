/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.server

import com.automq.stream.s3.Constants
import com.automq.stream.s3.metadata.ObjectUtils
import kafka.autobalancer.AutoBalancerManager
import kafka.autobalancer.services.AutoBalancerService
import kafka.automq.controller.DefaultQuorumControllerExtension
import kafka.controller.streamaspect.client.{Context, StreamClientFactoryProxy}
import kafka.migration.MigrationPropagator
import kafka.network.{DataPlaneAcceptor, SocketServer}
import kafka.raft.KafkaRaftManager
import kafka.server.QuotaFactory.QuotaManagers

import scala.collection.immutable
import kafka.server.metadata.{AclPublisher, ClientQuotaMetadataManager, DelegationTokenPublisher, DynamicClientQuotaPublisher, DynamicConfigPublisher, KRaftMetadataCache, KRaftMetadataCachePublisher, ScramPublisher}
import kafka.server.streamaspect.ElasticControllerApis
import kafka.utils.{CoreUtils, Logging}
import kafka.zk.{KafkaZkClient, ZkMigrationClient}
import org.apache.kafka.common.message.ApiMessageType.ListenerType
import org.apache.kafka.common.network.ListenerName
import org.apache.kafka.common.security.scram.internals.ScramMechanism
import org.apache.kafka.common.security.token.delegation.internals.DelegationTokenCache
import org.apache.kafka.common.utils.LogContext
import org.apache.kafka.common.{ClusterResource, Endpoint, Reconfigurable, Uuid}
import org.apache.kafka.controller.metrics.{ControllerMetadataMetricsPublisher, QuorumControllerMetrics}
import org.apache.kafka.controller.{QuorumController, QuorumControllerExtension, QuorumFeatures}
import org.apache.kafka.image.publisher.{ControllerRegistrationsPublisher, MetadataPublisher}
import org.apache.kafka.metadata.{KafkaConfigSchema, ListenerInfo}
import org.apache.kafka.metadata.authorizer.ClusterMetadataAuthorizer
import org.apache.kafka.metadata.bootstrap.BootstrapMetadata
import org.apache.kafka.metadata.migration.{KRaftMigrationDriver, LegacyPropagator}
import org.apache.kafka.metadata.placement.{ReplicaPlacer, StripedReplicaPlacer}
import org.apache.kafka.metadata.publisher.FeaturesPublisher
import org.apache.kafka.raft.QuorumConfig
import org.apache.kafka.security.{CredentialProvider, PasswordEncoder}
import org.apache.kafka.server.NodeToControllerChannelManager
import org.apache.kafka.server.authorizer.Authorizer
import org.apache.kafka.server.config.ServerLogConfigs.{ALTER_CONFIG_POLICY_CLASS_NAME_CONFIG, CREATE_TOPIC_POLICY_CLASS_NAME_CONFIG}
import org.apache.kafka.server.common.ApiMessageAndVersion
import org.apache.kafka.server.config.ConfigType
import org.apache.kafka.server.metrics.{KafkaMetricsGroup, KafkaYammerMetrics, LinuxIoMetricsCollector}
import org.apache.kafka.server.network.{EndpointReadyFutures, KafkaAuthorizerServerInfo}
import org.apache.kafka.server.policy.{AlterConfigPolicy, CreateTopicPolicy}
import org.apache.kafka.server.util.{Deadline, FutureUtils}

import java.util
import java.util.{Optional, OptionalLong, Random}
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.compat.java8.OptionConverters._
import scala.jdk.CollectionConverters._


case class ControllerMigrationSupport(
  zkClient: KafkaZkClient,
  migrationDriver: KRaftMigrationDriver,
  brokersRpcClient: LegacyPropagator
) {
  def shutdown(logging: Logging): Unit = {
    if (zkClient != null) {
      CoreUtils.swallow(zkClient.close(), logging)
    }
    if (brokersRpcClient != null) {
      CoreUtils.swallow(brokersRpcClient.shutdown(), logging)
    }
    if (migrationDriver != null) {
      CoreUtils.swallow(migrationDriver.close(), logging)
    }
  }
}

/**
 * A Kafka controller that runs in KRaft (Kafka Raft) mode.
 */
class ControllerServer(
  val sharedServer: SharedServer,
  val configSchema: KafkaConfigSchema,
  val bootstrapMetadata: BootstrapMetadata
) extends Logging {

  import kafka.server.Server._

  private val metricsGroup = new KafkaMetricsGroup(this.getClass)

  val config = sharedServer.controllerConfig
  val logContext = new LogContext(s"[ControllerServer id=${config.nodeId}] ")
  val time = sharedServer.time
  def metrics = sharedServer.metrics
  def raftManager: KafkaRaftManager[ApiMessageAndVersion] = sharedServer.raftManager

  val lock = new ReentrantLock()
  val awaitShutdownCond = lock.newCondition()
  var status: ProcessStatus = SHUTDOWN

  var linuxIoMetricsCollector: LinuxIoMetricsCollector = _
  @volatile var authorizer: Option[Authorizer] = None
  var tokenCache: DelegationTokenCache = _
  var credentialProvider: CredentialProvider = _
  var socketServer: SocketServer = _
  val socketServerFirstBoundPortFuture = new CompletableFuture[Integer]()
  var createTopicPolicy: Option[CreateTopicPolicy] = None
  var alterConfigPolicy: Option[AlterConfigPolicy] = None
  @volatile var quorumControllerMetrics: QuorumControllerMetrics = _
  var controller: QuorumController = _
  var quotaManagers: QuotaManagers = _
  var clientQuotaMetadataManager: ClientQuotaMetadataManager = _
  var controllerApis: ControllerApis = _
  var controllerApisHandlerPool: KafkaRequestHandlerPool = _
  var migrationSupport: Option[ControllerMigrationSupport] = None
  def kafkaYammerMetrics: KafkaYammerMetrics = KafkaYammerMetrics.INSTANCE
  val metadataPublishers: util.List[MetadataPublisher] = new util.ArrayList[MetadataPublisher]()
  @volatile var metadataCache : KRaftMetadataCache = _
  @volatile var metadataCachePublisher: KRaftMetadataCachePublisher = _
  @volatile var featuresPublisher: FeaturesPublisher = _
  @volatile var registrationsPublisher: ControllerRegistrationsPublisher = _
  @volatile var incarnationId: Uuid = _
  @volatile var registrationManager: ControllerRegistrationManager = _
  @volatile var registrationChannelManager: NodeToControllerChannelManager = _

  var autoBalancerManager: AutoBalancerService = _

  protected def buildAutoBalancerManager: AutoBalancerService = {
    new AutoBalancerManager(time, config.props, controller, raftManager.client)
  }

  private def maybeChangeStatus(from: ProcessStatus, to: ProcessStatus): Boolean = {
    lock.lock()
    try {
      if (status != from) return false
      status = to
      if (to == SHUTDOWN) awaitShutdownCond.signalAll()
    } finally {
      lock.unlock()
    }
    true
  }

  def clusterId: String = sharedServer.clusterId

  def startup(): Unit = {
    if (!maybeChangeStatus(SHUTDOWN, STARTING)) return
    val startupDeadline = Deadline.fromDelay(time, config.serverMaxStartupTimeMs, TimeUnit.MILLISECONDS)
    try {
      this.logIdent = logContext.logPrefix()
      info("Starting controller")
      config.dynamicConfig.initialize(zkClientOpt = None, clientMetricsReceiverPluginOpt = None)

      maybeChangeStatus(STARTING, STARTED)

      metricsGroup.newGauge("ClusterId", () => clusterId)
      metricsGroup.newGauge("yammer-metrics-count", () =>  KafkaYammerMetrics.defaultRegistry.allMetrics.size)

      linuxIoMetricsCollector = new LinuxIoMetricsCollector("/proc", time)
      if (linuxIoMetricsCollector.usable()) {
        metricsGroup.newGauge("linux-disk-read-bytes", () => linuxIoMetricsCollector.readBytes())
        metricsGroup.newGauge("linux-disk-write-bytes", () => linuxIoMetricsCollector.writeBytes())
      }

      authorizer = config.createNewAuthorizer()
      authorizer.foreach(_.configure(config.originals))

      metadataCache = MetadataCache.kRaftMetadataCache(config.nodeId, () => raftManager.client.kraftVersion())

      metadataCachePublisher = new KRaftMetadataCachePublisher(metadataCache)

      featuresPublisher = new FeaturesPublisher(logContext)

      registrationsPublisher = new ControllerRegistrationsPublisher()

      incarnationId = Uuid.randomUuid()

      val apiVersionManager = new SimpleApiVersionManager(
        ListenerType.CONTROLLER,
        config.unstableApiVersionsEnabled,
        config.migrationEnabled,
        () => featuresPublisher.features()
      )

      tokenCache = new DelegationTokenCache(ScramMechanism.mechanismNames)
      credentialProvider = new CredentialProvider(ScramMechanism.mechanismNames, tokenCache)
      socketServer = new SocketServer(config,
        metrics,
        time,
        credentialProvider,
        apiVersionManager)

      val listenerInfo = ListenerInfo
        .create(config.effectiveAdvertisedControllerListeners.map(_.toJava).asJava)
        .withWildcardHostnamesResolved()
        .withEphemeralPortsCorrected(name => socketServer.boundPort(new ListenerName(name)))
      socketServerFirstBoundPortFuture.complete(listenerInfo.firstListener().port())

      val endpointReadyFutures = {
        val builder = new EndpointReadyFutures.Builder()
        builder.build(authorizer.asJava,
          new KafkaAuthorizerServerInfo(
            new ClusterResource(clusterId),
            config.nodeId,
            listenerInfo.listeners().values(),
            listenerInfo.firstListener(),
            config.earlyStartListeners.map(_.value()).asJava))
      }

      sharedServer.startForController(listenerInfo)

      createTopicPolicy = Option(config.
        getConfiguredInstance(CREATE_TOPIC_POLICY_CLASS_NAME_CONFIG, classOf[CreateTopicPolicy]))
      alterConfigPolicy = Option(config.
        getConfiguredInstance(ALTER_CONFIG_POLICY_CLASS_NAME_CONFIG, classOf[AlterConfigPolicy]))

      val voterConnections = FutureUtils.waitWithLogging(logger.underlying, logIdent,
        "controller quorum voters future",
        sharedServer.controllerQuorumVotersFuture,
        startupDeadline, time)
      val controllerNodes = QuorumConfig.voterConnectionsToNodes(voterConnections)
      val quorumFeatures = new QuorumFeatures(config.nodeId,
        QuorumFeatures.defaultFeatureMap(config.unstableFeatureVersionsEnabled),
        controllerNodes.asScala.map(node => Integer.valueOf(node.id())).asJava)

      val delegationTokenKeyString = {
        if (config.tokenAuthEnabled) {
          config.delegationTokenSecretKey.value
        } else {
          null
        }
      }

      val controllerBuilder = {
        val leaderImbalanceCheckIntervalNs = if (config.autoLeaderRebalanceEnable) {
          OptionalLong.of(TimeUnit.NANOSECONDS.convert(config.leaderImbalanceCheckIntervalSeconds, TimeUnit.SECONDS))
        } else {
          OptionalLong.empty()
        }

        val maxIdleIntervalNs = config.metadataMaxIdleIntervalNs.fold(OptionalLong.empty)(OptionalLong.of)

        quorumControllerMetrics = new QuorumControllerMetrics(Optional.of(KafkaYammerMetrics.defaultRegistry), time, config.migrationEnabled)

        // AutoMQ for Kafka inject start
        val context = new Context()
        context.kafkaConfig = config
        context.controllerServer = this
        val streamClient = StreamClientFactoryProxy.get(context)

        ObjectUtils.setNamespace(Constants.DEFAULT_NAMESPACE + clusterId)
        // AutoMQ for Kafka inject end

        new QuorumController.Builder(config.nodeId, sharedServer.clusterId).
          setTime(time).
          setThreadNamePrefix(s"quorum-controller-${config.nodeId}-").
          setConfigSchema(configSchema).
          setRaftClient(raftManager.client).
          setQuorumFeatures(quorumFeatures).
          setDefaultReplicationFactor(config.defaultReplicationFactor.toShort).
          setDefaultNumPartitions(config.numPartitions.intValue()).
          setDefaultMinIsr(config.minInSyncReplicas.intValue()).
          setSessionTimeoutNs(TimeUnit.NANOSECONDS.convert(config.brokerSessionTimeoutMs.longValue(),
            TimeUnit.MILLISECONDS)).
          setLeaderImbalanceCheckIntervalNs(leaderImbalanceCheckIntervalNs).
          setMaxIdleIntervalNs(maxIdleIntervalNs).
          setMetrics(quorumControllerMetrics).
          setCreateTopicPolicy(createTopicPolicy.asJava).
          setAlterConfigPolicy(alterConfigPolicy.asJava).
          setConfigurationValidator(new ControllerConfigurationValidator(sharedServer.brokerConfig)).
          setStaticConfig(config.originals).
          setBootstrapMetadata(bootstrapMetadata).
          setFatalFaultHandler(sharedServer.fatalQuorumControllerFaultHandler).
          setNonFatalFaultHandler(sharedServer.nonFatalQuorumControllerFaultHandler).
          setZkMigrationEnabled(config.migrationEnabled).
          setDelegationTokenCache(tokenCache).
          setDelegationTokenSecretKey(delegationTokenKeyString).
          setDelegationTokenMaxLifeMs(config.delegationTokenMaxLifeMs).
          setDelegationTokenExpiryTimeMs(config.delegationTokenExpiryTimeMs).
          setDelegationTokenExpiryCheckIntervalMs(config.delegationTokenExpiryCheckIntervalMs).
          setEligibleLeaderReplicasEnabled(config.elrEnabled).
          setStreamClient(streamClient).
          setExtension(c => quorumControllerExtension(c)).
          setQuorumVoters(config.quorumVoters).
          setReplicaPlacer(replicaPlacer())
      }
      controller = controllerBuilder.build()

      // If we are using a ClusterMetadataAuthorizer, requests to add or remove ACLs must go
      // through the controller.
      authorizer match {
        case Some(a: ClusterMetadataAuthorizer) => a.setAclMutator(controller)
        case _ =>
      }

      if (config.migrationEnabled) {
        val zkClient = KafkaZkClient.createZkClient("KRaft Migration", time, config, KafkaServer.zkClientConfigFromKafkaConfig(config))
        val zkConfigEncoder = config.passwordEncoderSecret match {
          case Some(secret) => PasswordEncoder.encrypting(secret,
            config.passwordEncoderKeyFactoryAlgorithm,
            config.passwordEncoderCipherAlgorithm,
            config.passwordEncoderKeyLength,
            config.passwordEncoderIterations)
          case None => PasswordEncoder.NOOP
        }
        val migrationClient = ZkMigrationClient(zkClient, zkConfigEncoder)
        val propagator: LegacyPropagator = new MigrationPropagator(config.nodeId, config)
        val migrationDriver = KRaftMigrationDriver.newBuilder()
          .setNodeId(config.nodeId)
          .setZkRecordConsumer(controller.zkRecordConsumer())
          .setZkMigrationClient(migrationClient)
          .setPropagator(propagator)
          .setInitialZkLoadHandler(publisher => sharedServer.loader.installPublishers(java.util.Collections.singletonList(publisher)))
          .setFaultHandler(sharedServer.faultHandlerFactory.build(
            "zk migration",
            fatal = false,
            () => {}
          ))
          .setQuorumFeatures(quorumFeatures)
          .setConfigSchema(configSchema)
          .setControllerMetrics(quorumControllerMetrics)
          .setMinMigrationBatchSize(config.migrationMetadataMinBatchSize)
          .setTime(time)
          .build()
        migrationDriver.start()
        migrationSupport = Some(ControllerMigrationSupport(zkClient, migrationDriver, propagator))
      }

      quotaManagers = QuotaFactory.instantiate(config,
        metrics,
        time,
        s"controller-${config.nodeId}-")
      clientQuotaMetadataManager = new ClientQuotaMetadataManager(quotaManagers, socketServer.connectionQuotas)
      controllerApis = new ElasticControllerApis(socketServer.dataPlaneRequestChannel,
        authorizer,
        quotaManagers,
        time,
        controller,
        raftManager,
        config,
        clusterId,
        registrationsPublisher,
        apiVersionManager,
        metadataCache)
      controllerApisHandlerPool = new KafkaRequestHandlerPool(config.nodeId,
        socketServer.dataPlaneRequestChannel,
        controllerApis,
        time,
        config.numIoThreads,
        s"${DataPlaneAcceptor.MetricPrefix}RequestHandlerAvgIdlePercent",
        DataPlaneAcceptor.ThreadPrefix,
        "controller")

      autoBalancerManager = buildAutoBalancerManager

      // Set up the metadata cache publisher.
      metadataPublishers.add(metadataCachePublisher)

      // Set up the metadata features publisher.
      metadataPublishers.add(featuresPublisher)

      // Set up the controller registrations publisher.
      metadataPublishers.add(registrationsPublisher)

      // Create the registration manager, which handles sending KIP-919 controller registrations.
      registrationManager = new ControllerRegistrationManager(config.nodeId,
        clusterId,
        time,
        s"controller-${config.nodeId}-",
        QuorumFeatures.defaultFeatureMap(config.unstableFeatureVersionsEnabled),
        config.migrationEnabled,
        incarnationId,
        listenerInfo)

      // Add the registration manager to the list of metadata publishers, so that it receives
      // callbacks when the cluster registrations change.
      metadataPublishers.add(registrationManager)

      // Set up the dynamic config publisher. This runs even in combined mode, since the broker
      // has its own separate dynamic configuration object.
      metadataPublishers.add(new DynamicConfigPublisher(
        config,
        sharedServer.metadataPublishingFaultHandler,
        immutable.Map[String, ConfigHandler](
          // controllers don't host topics, so no need to do anything with dynamic topic config changes here
          ConfigType.BROKER -> new BrokerConfigHandler(config, quotaManagers)
        ),
        "controller"))

      // Register this instance for dynamic config changes to the KafkaConfig. This must be called
      // after the authorizer and quotaManagers are initialized, since it references those objects.
      // It must be called before DynamicClientQuotaPublisher is installed, since otherwise we may
      // miss the initial update which establishes the dynamic configurations that are in effect on
      // startup.
      config.dynamicConfig.addReconfigurables(this)

      // Set up the client quotas publisher. This will enable controller mutation quotas and any
      // other quotas which are applicable.
      metadataPublishers.add(new DynamicClientQuotaPublisher(
        config,
        sharedServer.metadataPublishingFaultHandler,
        "controller",
        clientQuotaMetadataManager))

      // Set up the SCRAM publisher.
      metadataPublishers.add(new ScramPublisher(
        config,
        sharedServer.metadataPublishingFaultHandler,
        "controller",
        credentialProvider
      ))

      // Set up the DelegationToken publisher.
      // We need a tokenManager for the Publisher
      // The tokenCache in the tokenManager is the same used in DelegationTokenControlManager
      metadataPublishers.add(new DelegationTokenPublisher(
          config,
          sharedServer.metadataPublishingFaultHandler,
          "controller",
          new DelegationTokenManager(config, tokenCache, time)
      ))

      // Set up the metrics publisher.
      metadataPublishers.add(new ControllerMetadataMetricsPublisher(
        sharedServer.controllerServerMetrics,
        sharedServer.metadataPublishingFaultHandler
      ))

      // Set up the ACL publisher.
      metadataPublishers.add(new AclPublisher(
        config.nodeId,
        sharedServer.metadataPublishingFaultHandler,
        "controller",
        authorizer
      ))

      // Install all metadata publishers.
      FutureUtils.waitWithLogging(logger.underlying, logIdent,
        "the controller metadata publishers to be installed",
        sharedServer.loader.installPublishers(metadataPublishers), startupDeadline, time)

      val authorizerFutures: Map[Endpoint, CompletableFuture[Void]] = endpointReadyFutures.futures().asScala.toMap

      /**
       * Enable the controller endpoint(s). If we are using an authorizer which stores
       * ACLs in the metadata log, such as StandardAuthorizer, we will be able to start
       * accepting requests from principals included super.users right after this point,
       * but we will not be able to process requests from non-superusers until AclPublisher
       * publishes metadata from the QuorumController. MetadataPublishers do not publish
       * metadata until the controller has caught up to the high watermark.
       */
      val socketServerFuture = socketServer.enableRequestProcessing(authorizerFutures)

      /**
       * Start the KIP-919 controller registration manager.
       */
      val controllerNodeProvider = RaftControllerNodeProvider(raftManager, config)
      registrationChannelManager = new NodeToControllerChannelManagerImpl(
        controllerNodeProvider,
        time,
        metrics,
        config,
        "registration",
        s"controller-${config.nodeId}-",
        5000)
      registrationChannelManager.start()
      registrationManager.start(registrationChannelManager)

      // Block here until all the authorizer futures are complete
      FutureUtils.waitWithLogging(logger.underlying, logIdent,
        "all of the authorizer futures to be completed",
        CompletableFuture.allOf(authorizerFutures.values.toSeq: _*), startupDeadline, time)

      // Wait for all the SocketServer ports to be open, and the Acceptors to be started.
      FutureUtils.waitWithLogging(logger.underlying, logIdent,
        "all of the SocketServer Acceptors to be started",
        socketServerFuture, startupDeadline, time)
    } catch {
      case e: Throwable =>
        maybeChangeStatus(STARTING, STARTED)
        sharedServer.controllerStartupFaultHandler.handleFault("caught exception", e)
        shutdown()
        throw e
    }
  }

  def shutdown(): Unit = {
    if (!maybeChangeStatus(STARTED, SHUTTING_DOWN)) return
    try {
      info("shutting down")
      // Ensure that we're not the Raft leader prior to shutting down our socket server, for a
      // smoother transition.
      sharedServer.ensureNotRaftLeader()
      if (autoBalancerManager != null) {
        autoBalancerManager.shutdown()
      }

      incarnationId = null
      if (registrationManager != null) {
        CoreUtils.swallow(registrationManager.close(), this)
        registrationManager = null
      }
      if (registrationChannelManager != null) {
        CoreUtils.swallow(registrationChannelManager.shutdown(), this)
        registrationChannelManager = null
      }
      metadataPublishers.forEach(p => sharedServer.loader.removeAndClosePublisher(p).get())
      metadataPublishers.clear()
      if (metadataCache != null) {
        metadataCache = null
      }
      if (metadataCachePublisher != null) {
        metadataCachePublisher.close()
        metadataCachePublisher = null
      }
      if (featuresPublisher != null) {
        featuresPublisher.close()
        featuresPublisher = null
      }
      if (registrationsPublisher != null) {
        registrationsPublisher.close()
        registrationsPublisher = null
      }
      if (socketServer != null)
        CoreUtils.swallow(socketServer.stopProcessingRequests(), this)
      migrationSupport.foreach(_.shutdown(this))
      if (controller != null)
        controller.beginShutdown()
      if (socketServer != null)
        CoreUtils.swallow(socketServer.shutdown(), this)
      if (controllerApisHandlerPool != null)
        CoreUtils.swallow(controllerApisHandlerPool.shutdown(), this)
      if (controllerApis != null)
        CoreUtils.swallow(controllerApis.close(), this)
      if (quotaManagers != null)
        CoreUtils.swallow(quotaManagers.shutdown(), this)
      if (controller != null)
        controller.close()
      if (quorumControllerMetrics != null)
        CoreUtils.swallow(quorumControllerMetrics.close(), this)
      CoreUtils.swallow(authorizer.foreach(_.close()), this)
      createTopicPolicy.foreach(policy => CoreUtils.swallow(policy.close(), this))
      alterConfigPolicy.foreach(policy => CoreUtils.swallow(policy.close(), this))
      socketServerFirstBoundPortFuture.completeExceptionally(new RuntimeException("shutting down"))
      CoreUtils.swallow(config.dynamicConfig.clear(), this)
      sharedServer.stopForController()
    } catch {
      case e: Throwable =>
        fatal("Fatal error during controller shutdown.", e)
        throw e
    } finally {
      maybeChangeStatus(SHUTTING_DOWN, SHUTDOWN)
    }
  }

  def awaitShutdown(): Unit = {
    lock.lock()
    try {
      while (true) {
        if (status == SHUTDOWN) return
        awaitShutdownCond.awaitUninterruptibly()
      }
    } finally {
      lock.unlock()
    }
  }

  // AutoMQ for Kafka inject start
  protected def quorumControllerExtension(quorumController: QuorumController): QuorumControllerExtension = {
    new DefaultQuorumControllerExtension(quorumController)
  }

  protected def replicaPlacer(): ReplicaPlacer = {
    new StripedReplicaPlacer(new Random())
  }

  // return a list of all reconfigurable objects
  def reconfigurables(): java.util.List[Reconfigurable] = {
    java.util.List.of(
      autoBalancerManager
    )
  }
  // AutoMQ for Kafka inject end
}
