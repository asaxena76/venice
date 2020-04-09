package com.linkedin.davinci.client;

import com.linkedin.venice.ConfigKeys;
import com.linkedin.venice.client.exceptions.VeniceClientException;
import com.linkedin.venice.client.schema.SchemaReader;
import com.linkedin.venice.client.store.ClientConfig;
import com.linkedin.venice.client.store.ClientFactory;
import com.linkedin.venice.client.store.D2ServiceDiscovery;
import com.linkedin.venice.client.store.transport.D2TransportClient;
import com.linkedin.venice.client.store.transport.TransportClient;
import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.compression.CompressorFactory;
import com.linkedin.venice.controller.init.SystemSchemaInitializationRoutine;
import com.linkedin.venice.controllerapi.D2ServiceDiscoveryResponseV2;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.helix.HelixAdapterSerializer;
import com.linkedin.venice.helix.HelixReadOnlySchemaRepository;
import com.linkedin.venice.helix.HelixReadOnlyStoreRepository;
import com.linkedin.venice.helix.ZkClientFactory;
import com.linkedin.venice.kafka.consumer.KafkaStoreIngestionService;
import com.linkedin.venice.meta.ReadOnlySchemaRepository;
import com.linkedin.venice.meta.ReadOnlyStoreRepository;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.serialization.avro.AvroProtocolDefinition;
import com.linkedin.venice.serializer.FastSerializerDeserializerFactory;
import com.linkedin.venice.serializer.RecordDeserializer;
import com.linkedin.venice.serializer.RecordSerializer;
import com.linkedin.venice.serializer.SerializerDeserializerFactory;
import com.linkedin.venice.server.VeniceConfigLoader;
import com.linkedin.venice.service.AbstractVeniceService;
import com.linkedin.venice.stats.AggVersionedStorageEngineStats;
import com.linkedin.venice.stats.TehutiUtils;
import com.linkedin.venice.stats.ZkClientStatusStats;
import com.linkedin.venice.storage.StorageEngineMetadataService;
import com.linkedin.venice.storage.StorageService;
import com.linkedin.venice.storage.chunking.SingleGetChunkingAdapter;
import com.linkedin.venice.store.AbstractStorageEngine;
import com.linkedin.venice.store.record.ValueRecord;
import com.linkedin.venice.utils.ConcurrentRef;
import com.linkedin.venice.utils.PropertyBuilder;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.VeniceProperties;
import com.linkedin.venice.utils.concurrent.VeniceConcurrentHashMap;

import io.tehuti.metrics.MetricsRepository;

import org.apache.avro.Schema;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.helix.manager.zk.ZkClient;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


public class AvroGenericDaVinciClientImpl<K, V> implements DaVinciClient<K, V> {
  private static String DAVINCI_CLIENT_NAME = "davinci_client";
  private static int REFRESH_ATTEMPTS_FOR_ZK_RECONNECT = 1;
  private static int REFRESH_INTERVAL_FOR_ZK_RECONNECT_IN_MS = 1;
  private static final Logger logger = Logger.getLogger(AvroGenericDaVinciClientImpl.class);

  private final String storeName;
  private final boolean useFastAvro;
  private final MetricsRepository metricsRepository;
  private final DaVinciConfig daVinciConfig;
  private final ClientConfig clientConfig;
  private final List<AbstractVeniceService> services = new ArrayList<>();
  private final AtomicBoolean isStarted = new AtomicBoolean(false);
  private final Set<Integer> subscribedPartitions = VeniceConcurrentHashMap.newKeySet();

  private ZkClient zkClient;
  private ReadOnlyStoreRepository metadataReposotory;
  private ReadOnlySchemaRepository schemaRepository;
  private StorageService storageService;
  private StorageEngineMetadataService storageMetadataService;
  private KafkaStoreIngestionService kafkaStoreIngestionService;
  private RecordSerializer<K> keySerializer;
  private DaVinciPartitioner partitioner;
  private IngestionController ingestionController;

  public AvroGenericDaVinciClientImpl(
      DaVinciConfig daVinciConfig,
      ClientConfig clientConfig) {
    this.daVinciConfig = daVinciConfig;
    this.clientConfig = clientConfig;
    this.storeName = clientConfig.getStoreName();
    this.useFastAvro = clientConfig.isUseFastAvro();
    this.metricsRepository = Optional.ofNullable(clientConfig.getMetricsRepository())
        .orElse(TehutiUtils.getMetricsRepository(DAVINCI_CLIENT_NAME));
  }

  private D2TransportClient getD2TransportClient() {
    TransportClient transportClient = ClientFactory.getTransportClient(clientConfig);
    if (!(transportClient instanceof D2TransportClient)) {
      throw new VeniceClientException("Da Vinci only supports D2 client.");
    }
    return (D2TransportClient) transportClient;
  }

  private VeniceConfigLoader buildVeniceConfigLoader() {
    final D2ServiceDiscoveryResponseV2 d2ServiceDiscoveryResponse;
    try (D2TransportClient d2TransportClient = getD2TransportClient()) {
      D2ServiceDiscovery d2ServiceDiscovery = new D2ServiceDiscovery();
      d2ServiceDiscoveryResponse = d2ServiceDiscovery.discoverD2Service(d2TransportClient, getStoreName());
    }
    String zkAddress = d2ServiceDiscoveryResponse.getZkAddress();
    String kafkaZkAddress = d2ServiceDiscoveryResponse.getKafkaZkAddress();
    String kafkaBootstrapServers = d2ServiceDiscoveryResponse.getKafkaBootstrapServers();
    String clusterName = d2ServiceDiscoveryResponse.getCluster();
    VeniceProperties clusterProperties = new PropertyBuilder()
        // Helix-related config
        .put(ConfigKeys.ZOOKEEPER_ADDRESS, zkAddress)
        // Kafka-related config
        .put(ConfigKeys.KAFKA_BOOTSTRAP_SERVERS, kafkaBootstrapServers)
        .put(ConfigKeys.KAFKA_ZK_ADDRESS, kafkaZkAddress)
        // Other configs
        .put(ConfigKeys.CLUSTER_NAME, clusterName)
        .put(ConfigKeys.PERSISTENCE_TYPE, daVinciConfig.getPersistenceType())
        .build();
    // Generate server.properties in config directory
    VeniceProperties serverProperties = new PropertyBuilder()
        .put(ConfigKeys.LISTENER_PORT, 0) // not used by Da Vinci
        .put(ConfigKeys.DATA_BASE_PATH, daVinciConfig.getDataBasePath())
        .build();
    VeniceProperties serverOverrideProperties = new VeniceProperties(new Properties());
    VeniceConfigLoader
        veniceConfigLoader = new VeniceConfigLoader(clusterProperties, serverProperties, serverOverrideProperties);
    return veniceConfigLoader;
  }

  @Override
  public CompletableFuture<Void> subscribeToAllPartitions() {
    // TODO: add non-static partitioning support
    Store store = metadataReposotory.getStoreOrThrow(storeName);
    String msg = "Cannot subscribe to an empty store " + storeName + ". Please push data to the store first.";
    Version version = store.getVersions().stream().findAny().orElseThrow(() -> new VeniceClientException(msg));
    Set<Integer> partitions = IntStream.range(0, version.getPartitionCount()).boxed().collect(Collectors.toSet());
    return subscribe(partitions);
  }

  @Override
  public CompletableFuture<Void> subscribe(Set<Integer> partitions) {
    subscribedPartitions.addAll(partitions);
    return ingestionController.subscribe(getStoreName(), partitions);
  }

  @Override
  public CompletableFuture<Void> unsubscribe(Set<Integer> partitions) {
    subscribedPartitions.removeAll(partitions);
    return ingestionController.unsubscribe(getStoreName(), partitions);
  }

  @Override
  public CompletableFuture<V> get(K key) throws VeniceClientException {
    if (!isStarted()) {
      throw new VeniceClientException("Client is not started.");
    }

    // TODO: refactor IngestionController to use StoreBackend directly
    try (ConcurrentRef<IngestionController.VersionBackend> versionRef =
             ingestionController.getStoreOrThrow(storeName).getCurrentVersion()) {
      if (versionRef.get() == null) {
        throw new VeniceClientException("Failed to find a ready store version.");
      }

      Version version = versionRef.get().getVersion();
      String topic = version.kafkaTopicName();
      byte[] keyBytes = keySerializer.serialize(key);
      int partitionId = partitioner.getPartitionId(keyBytes, version.getPartitionCount());

      // Make sure the partition id is within client's subscription.
      if (!subscribedPartitions.contains(partitionId)) {
        throw new VeniceClientException("DaVinci client does not subscribe to the partition " + partitionId + " in version " + version.getNumber());
      }

      AbstractStorageEngine storageEngine = storageService.getStorageEngineRepository().getLocalStorageEngine(topic);
      if (storageEngine == null) {
        throw new VeniceClientException("Failed to find a ready store version.");
      }

      ValueRecord valueRecord = SingleGetChunkingAdapter.get(storageEngine, partitionId, keyBytes, version.isChunkingEnabled(), null);
      if (valueRecord == null) {
        return CompletableFuture.completedFuture(null);
      }

      ByteBuffer data = decompressRecord(version.getCompressionStrategy(), ByteBuffer.wrap(valueRecord.getDataInBytes()));
      RecordDeserializer<V> deserializer = getDataRecordDeserializer(valueRecord.getSchemaId());
      return CompletableFuture.completedFuture(deserializer.deserialize(data));
    }
  }

  @Override
  public CompletableFuture<Map<K, V>> batchGet(Set<K> keys) throws VeniceClientException {
    throw new UnsupportedOperationException();
  }

  @Override
  public synchronized void start() throws VeniceClientException {
    boolean isntStarted = isStarted.compareAndSet(false, true);
    if (!isntStarted) {
      throw new VeniceClientException("Client is already started!");
    }
    VeniceConfigLoader veniceConfigLoader = buildVeniceConfigLoader();
    String clusterName = veniceConfigLoader.getVeniceClusterConfig().getClusterName();
    zkClient = ZkClientFactory.newZkClient(veniceConfigLoader.getVeniceClusterConfig().getZookeeperAddress());
    zkClient.subscribeStateChanges(new ZkClientStatusStats(metricsRepository, "davinci-zk-client"));
    HelixAdapterSerializer adapter = new HelixAdapterSerializer();
    metadataReposotory = new HelixReadOnlyStoreRepository(zkClient, adapter, clusterName,
        REFRESH_ATTEMPTS_FOR_ZK_RECONNECT, REFRESH_INTERVAL_FOR_ZK_RECONNECT_IN_MS);
    metadataReposotory.refresh();
    schemaRepository = new HelixReadOnlySchemaRepository(metadataReposotory, zkClient, adapter, clusterName,
        REFRESH_ATTEMPTS_FOR_ZK_RECONNECT, REFRESH_INTERVAL_FOR_ZK_RECONNECT_IN_MS);
    schemaRepository.refresh();

    AggVersionedStorageEngineStats
        storageEngineStats = new AggVersionedStorageEngineStats(metricsRepository, metadataReposotory);
    storageService = new StorageService(veniceConfigLoader, storageEngineStats);
    services.add(storageService);

    storageMetadataService = new StorageEngineMetadataService(storageService.getStorageEngineRepository());
    services.add(storageMetadataService);

    // SchemaReader of Kafka protocol
    SchemaReader schemaReader = ClientFactory.getSchemaReader(
        ClientConfig.cloneConfig(clientConfig).setStoreName(SystemSchemaInitializationRoutine.getSystemStoreName(AvroProtocolDefinition.KAFKA_MESSAGE_ENVELOPE)));
    kafkaStoreIngestionService = new KafkaStoreIngestionService(
        storageService.getStorageEngineRepository(),
        veniceConfigLoader,
        storageMetadataService,
        metadataReposotory,
        schemaRepository,
        metricsRepository,
        Optional.of(schemaReader),
        Optional.of(clientConfig));
    services.add(kafkaStoreIngestionService);
    this.keySerializer =
        SerializerDeserializerFactory.getAvroGenericSerializer(getKeySchema());
    // TODO: initiate ingestion service. pass in ingestionService as null to make it compile.
    this.partitioner = new DaVinciPartitioner(metadataReposotory.getStore(getStoreName()).getPartitionerConfig());

    ingestionController = new IngestionController(
        veniceConfigLoader,
        metadataReposotory,
        storageService,
        kafkaStoreIngestionService);

    logger.info("Starting " + services.size() + " services.");
    long start = System.currentTimeMillis();
    for (AbstractVeniceService service : services) {
      service.start();
    }
    ingestionController.start();
    long end = System.currentTimeMillis();
    logger.info("Startup completed in " + (end - start) + " ms.");
  }

  @Override
  public synchronized void close() {
    List<Exception> exceptions = new ArrayList<>();
    logger.info("Stopping all services ");

    /* Stop in reverse order */
    if (!isStarted()) {
      logger.info("The client is already stopped, ignoring duplicate attempt.");
      return;
    }

    ingestionController.close();
    for (AbstractVeniceService service : Utils.reversed(services)) {
      try {
        service.stop();
      } catch (Exception e) {
        exceptions.add(e);
        logger.error("Exception in stopping service: " + service.getName(), e);
      }
    }
    logger.info("All services stopped");

    if (exceptions.size() > 0) {
      throw new VeniceException(exceptions.get(0));
    }
    isStarted.set(false);

    metricsRepository.close();
    zkClient.close();
    isStarted.set(false);
  }

  @Override
  public String getStoreName() {
    return storeName;
  }

  @Override
  public Schema getKeySchema() {
    return schemaRepository.getKeySchema(getStoreName()).getSchema();
  }

  @Override
  public Schema getLatestValueSchema() {
    return schemaRepository.getLatestValueSchema(getStoreName()).getSchema();
  }

  public boolean isUseFastAvro() {
    return useFastAvro;
  }

  /**
   * @return true if the {@link AvroGenericDaVinciClientImpl} and all of its inner services are fully started
   *         false if the {@link AvroGenericDaVinciClientImpl} was not started or if any of its inner services
   *         are not finished starting.
   */
  public boolean isStarted() {
    return isStarted.get() && services.stream().allMatch(abstractVeniceService -> abstractVeniceService.isStarted());
  }

  private ByteBuffer decompressRecord(CompressionStrategy compressionStrategy, ByteBuffer data) {
    try {
      return CompressorFactory.getCompressor(compressionStrategy).decompress(data);
    } catch (IOException e) {
      throw new VeniceClientException(
          String.format("Unable to decompress the record, compressionStrategy=%d", compressionStrategy.getValue()), e);
    }
  }

  private RecordDeserializer<V> getDataRecordDeserializer(int schemaId) throws VeniceClientException {
    // Get latest value schema
    Schema readerSchema = schemaRepository.getLatestValueSchema(storeName).getSchema();
    if (null == readerSchema) {
      throw new VeniceClientException("Failed to get latest value schema for store: " + getStoreName());
    }

    Schema writerSchema = schemaRepository.getValueSchema(storeName, schemaId).getSchema();
    if (null == writerSchema) {
      throw new VeniceClientException("Failed to get value schema for store: " + getStoreName() + " and id: " + schemaId);
    }

    /**
     * The reason to fetch the latest value schema before fetching the writer schema since internally
     * it will fetch all the available value schemas when no value schema is present in {@link SchemaReader},
     * which means the latest value schema could be pretty accurate even the following read requests are
     * asking for older schema versions.
     *
     * The reason to fetch latest value schema again after fetching the writer schema is that the new fetched
     * writer schema could be newer than the cached value schema versions.
     * When the latest value schema is present in {@link SchemaReader}, the following invocation is very cheap.
     */
    if (isUseFastAvro()) {
      return FastSerializerDeserializerFactory.getFastAvroGenericDeserializer(writerSchema, readerSchema);
    } else {
      return SerializerDeserializerFactory.getAvroGenericDeserializer(writerSchema, readerSchema);
    }
  }
}